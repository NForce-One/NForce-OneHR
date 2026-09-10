package com.nforce.onehr.service;

import com.nforce.onehr.dto.doc.*;
import com.nforce.onehr.entity.DocumentType;
import com.nforce.onehr.entity.Employee;
import java.util.ArrayList;
import com.nforce.onehr.entity.EmployeeDocument;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final EmployeeDocumentRepository docRepo;
    private final DocumentTypeRepository docTypeRepo;
    private final EmployeeRepository employeeRepo;
    private final UserRepository userRepo;
    private final NotificationRepository notificationRepo;
    private final NotificationService notificationService;

    // ── Employee: list my documents with required doc computation ──

    @Transactional(readOnly = true)
    public List<EmployeeDocumentResponse> myDocuments(String actorEmail) {
        UUID actorId = requireUser(actorEmail).getId();
        checkExpiryReminders(actorId);
        return docRepo.findByEmployeeUserIdOrderByUploadedAtDesc(actorId).stream()
                .map(EmployeeDocumentResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RequiredDocumentDto> myRequiredDocuments(String actorEmail) {
        UUID actorId = requireUser(actorEmail).getId();
        return requiredDocumentsFor(actorId);
    }

    /**
     * Same computation as myRequiredDocuments, keyed by an arbitrary employee
     * rather than the caller — for other services (e.g. Onboarding) to reuse
     * without duplicating the required-document logic.
     */
    @Transactional(readOnly = true)
    public List<RequiredDocumentDto> requiredDocumentsFor(UUID employeeUserId) {
        Employee emp = employeeRepo.findById(employeeUserId).orElse(null);

        List<DocumentType> allActive = docTypeRepo.findByActiveTrueOrderByNameAsc();
        List<DocumentType> applicable = allActive.stream()
                .filter(dt -> isApplicable(dt, emp))
                .collect(Collectors.toList());

        Map<Integer, EmployeeDocument> uploaded = docRepo
                .findByEmployeeUserIdOrderByUploadedAtDesc(employeeUserId).stream()
                .collect(Collectors.toMap(d -> d.getDocumentType().getId(), d -> d, (a, b) -> a));

        return applicable.stream().map(dt -> {
            EmployeeDocument doc = uploaded.get(dt.getId());
            boolean expiringSoon = doc != null && doc.getExpiryDate() != null
                    && doc.getExpiryDate().isAfter(LocalDate.now())
                    && ChronoUnit.DAYS.between(LocalDate.now(), doc.getExpiryDate()) <= 30;
            return new RequiredDocumentDto(
                    dt.getId(), dt.getName(), dt.isRequiresVerification(), dt.isRequiresExpiryDate(),
                    doc != null, doc != null ? doc.getStatus() : null, expiringSoon);
        }).collect(Collectors.toList());
    }

    // ── Employee: upload or re-upload ──

    @Transactional
    public EmployeeDocumentResponse uploadDocument(String actorEmail, Integer documentTypeId,
                                                   MultipartFile file, LocalDate issueDate, LocalDate expiryDate) throws IOException {
        if (issueDate != null && expiryDate != null && expiryDate.isBefore(issueDate)) {
            throw new IllegalArgumentException("Expiry date cannot be earlier than issue date");
        }
        UUID actorId = requireUser(actorEmail).getId();
        DocumentType dt = docTypeRepo.findById(documentTypeId)
                .orElseThrow(() -> new NoSuchElementException("Document type not found: " + documentTypeId));

        Optional<EmployeeDocument> existing = docRepo.findByEmployeeUserIdAndDocumentTypeId(actorId, documentTypeId);
        EmployeeDocument doc;
        if (existing.isPresent()) {
            doc = existing.get();
            doc.setFileName(file.getOriginalFilename());
            doc.setFileUrl("/api/documents/" + doc.getId() + "/file");
            doc.setFileData(file.getBytes());
            doc.setIssueDate(issueDate);
            doc.setExpiryDate(expiryDate);
            if (dt.isRequiresVerification()) {
                doc.setStatus("PENDING_VERIFICATION");
                doc.setVerifiedBy(null);
                doc.setVerifiedAt(null);
                doc.setRejectionReason(null);
            }
        } else {
            doc = EmployeeDocument.builder()
                    .employeeUserId(actorId)
                    .documentType(dt)
                    .fileName(file.getOriginalFilename())
                    .fileUrl("")
                    .fileData(file.getBytes())
                    .issueDate(issueDate)
                    .expiryDate(expiryDate)
                    .status(dt.isRequiresVerification() ? "PENDING_VERIFICATION" : "VERIFIED")
                    .build();
            doc = docRepo.save(doc);
            doc.setFileUrl("/api/documents/" + doc.getId() + "/file");
        }
        doc = docRepo.save(doc);
        return EmployeeDocumentResponse.from(doc);
    }

    // ── HR/SA: list pending documents (excludes HR/SA employees) ──

    @Transactional(readOnly = true)
    public List<EmployeeDocumentResponse> listPending(String actorEmail) {
        requireAdminRole(actorEmail);
        Set<UUID> adminIds = userRepo.findAdminUserIds();
        List<EmployeeDocument> docs = docRepo.findByStatusOrderByUploadedAtDesc("PENDING_VERIFICATION")
                .stream().filter(d -> !adminIds.contains(d.getEmployeeUserId())).collect(Collectors.toList());
        Map<UUID, String> names = nameMapFor(docs.stream().map(EmployeeDocument::getEmployeeUserId).collect(Collectors.toSet()));
        return docs.stream().map(d -> EmployeeDocumentResponse.from(d, names.get(d.getEmployeeUserId()))).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeDocumentResponse> listAll(String actorEmail) {
        requireAdminRole(actorEmail);
        Set<UUID> adminIds = userRepo.findAdminUserIds();
        List<EmployeeDocument> docs = docRepo.findAllWithActiveEmployee()
                .stream().filter(d -> !adminIds.contains(d.getEmployeeUserId())).collect(Collectors.toList());
        Map<UUID, String> names = nameMapFor(docs.stream().map(EmployeeDocument::getEmployeeUserId).collect(Collectors.toSet()));
        return docs.stream().map(d -> EmployeeDocumentResponse.from(d, names.get(d.getEmployeeUserId()))).collect(Collectors.toList());
    }

    // ── HR/SA: verify or reject ──

    @Transactional
    public EmployeeDocumentResponse verifyDocument(String actorEmail, UUID documentId, VerifyDocumentRequest req) {
        User actor = requireUser(actorEmail);
        requireAdminRole(actorEmail);

        EmployeeDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));

        if (doc.getEmployeeUserId().equals(actor.getId())) {
            throw new AccessDeniedException("Cannot verify your own document");
        }
        if ("VERIFY".equalsIgnoreCase(req.getAction())) {
            doc.setStatus("VERIFIED");
            doc.setVerifiedBy(actor.getId());
            doc.setVerifiedAt(Instant.now());
            doc.setRejectionReason(null);
            notificationService.send(doc.getEmployeeUserId(), "DOCUMENT_VERIFIED",
                    "Document Verified",
                    "Your " + doc.getDocumentType().getName() + " has been verified.",
                    "/documents");
        } else if ("REJECT".equalsIgnoreCase(req.getAction())) {
            if (req.getRejectionReason() == null || req.getRejectionReason().isBlank()) {
                throw new IllegalArgumentException("Rejection reason is required");
            }
            doc.setStatus("REJECTED");
            doc.setVerifiedBy(actor.getId());
            doc.setVerifiedAt(Instant.now());
            doc.setRejectionReason(req.getRejectionReason());
            notificationService.send(doc.getEmployeeUserId(), "DOCUMENT_REJECTED",
                    "Document Rejected",
                    "Your " + doc.getDocumentType().getName() + " was rejected: " + req.getRejectionReason(),
                    "/documents");
        } else {
            throw new IllegalArgumentException("Invalid action: " + req.getAction());
        }
        return EmployeeDocumentResponse.from(docRepo.save(doc));
    }

    // ── Serve file (HR/SA only; employee may get own) ──

    @Transactional(readOnly = true)
    public EmployeeDocument getDocumentFile(String actorEmail, UUID documentId) {
        User actor = requireUser(actorEmail);
        EmployeeDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        boolean isAdmin = actor.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
        boolean isOwner = doc.getEmployeeUserId().equals(actor.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException("Access denied");
        }
        return doc;
    }

    // ── HR/SA: list missing documents (excludes HR/SA employees) ──

    @Transactional(readOnly = true)
    public List<MissingDocumentDto> listMissing(String actorEmail) {
        requireAdminRole(actorEmail);
        List<Employee> employees = employeeRepo.findAllActiveNonAdminWithDetails();
        List<DocumentType> docTypes = docTypeRepo.findByActiveTrueOrderByNameAsc();

        Set<String> uploaded = docRepo.findAllEmployeeDocTypePairs().stream()
                .map(r -> r[0].toString() + ":" + r[1].toString())
                .collect(Collectors.toSet());

        List<MissingDocumentDto> missing = new ArrayList<>();
        for (Employee emp : employees) {
            for (DocumentType dt : docTypes) {
                if (isApplicable(dt, emp) && !uploaded.contains(emp.getUserId().toString() + ":" + dt.getId().toString())) {
                    missing.add(new MissingDocumentDto(emp.getUserId(), emp.getFullName(), dt.getId(), dt.getName()));
                }
            }
        }
        return missing;
    }

    // ── HR/SA: remind employee about missing document ──

    @Transactional
    public void remindMissingDocument(String actorEmail, UUID employeeUserId, Integer documentTypeId) {
        requireAdminRole(actorEmail);
        DocumentType dt = docTypeRepo.findById(documentTypeId)
                .orElseThrow(() -> new NoSuchElementException("Document type not found: " + documentTypeId));
        notificationService.send(employeeUserId, "DOCUMENT_REMINDER",
                "Document Required: " + dt.getName(),
                "Please upload your " + dt.getName() + " document to complete your compliance profile.",
                "/documents");
    }

    // ── HR KPIs ──

    @Transactional(readOnly = true)
    public DocumentAdminKpiDto getAdminKpis(String actorEmail) {
        requireAdminRole(actorEmail);
        return new DocumentAdminKpiDto(
                docRepo.countPendingDocuments(),
                docRepo.countEmployeesWithPendingDocs(),
                docRepo.countExpiringWithin30Days(),
                docRepo.count());
    }

    // ── Employee compliance summary ──

    @Transactional(readOnly = true)
    public ComplianceSummaryDto getMyComplianceSummary(String actorEmail, long pendingPolicies) {
        UUID actorId = requireUser(actorEmail).getId();
        Employee emp = employeeRepo.findById(actorId).orElse(null);

        List<DocumentType> applicable = docTypeRepo.findByActiveTrueOrderByNameAsc().stream()
                .filter(dt -> isApplicable(dt, emp))
                .collect(Collectors.toList());

        Map<Integer, EmployeeDocument> uploaded = docRepo
                .findByEmployeeUserIdOrderByUploadedAtDesc(actorId).stream()
                .collect(Collectors.toMap(d -> d.getDocumentType().getId(), d -> d, (a, b) -> a));

        int totalRequired = applicable.size();
        int uploadedCount = 0, verified = 0, pending = 0, rejected = 0, missing = 0, expiringSoon = 0;

        for (DocumentType dt : applicable) {
            EmployeeDocument doc = uploaded.get(dt.getId());
            if (doc == null) {
                missing++;
            } else {
                uploadedCount++;
                switch (doc.getStatus()) {
                    case "VERIFIED" -> verified++;
                    case "PENDING_VERIFICATION" -> pending++;
                    case "REJECTED" -> rejected++;
                }
                if (doc.getExpiryDate() != null && doc.getExpiryDate().isAfter(LocalDate.now())
                        && ChronoUnit.DAYS.between(LocalDate.now(), doc.getExpiryDate()) <= 30) {
                    expiringSoon++;
                }
            }
        }
        return new ComplianceSummaryDto(totalRequired, uploadedCount, verified, pending, rejected, missing, expiringSoon, (int) pendingPolicies);
    }

    // ── Helpers ──

    private boolean isApplicable(DocumentType dt, Employee emp) {
        if (emp == null) return true;
        if (dt.getApplicableEmploymentTypes() != null && !dt.getApplicableEmploymentTypes().isBlank()) {
            List<String> types = Arrays.asList(dt.getApplicableEmploymentTypes().split(","));
            if (types.stream().noneMatch(t -> t.trim().equalsIgnoreCase(emp.getEmploymentType()))) return false;
        }
        if (dt.getApplicableLocations() != null && !dt.getApplicableLocations().isBlank()) {
            String locName = emp.getLocation() != null ? emp.getLocation().getName() : null;
            if (locName == null) return false;
            List<String> locs = Arrays.asList(dt.getApplicableLocations().split(","));
            if (locs.stream().noneMatch(l -> l.trim().equalsIgnoreCase(locName))) return false;
        }
        return true;
    }

    private void checkExpiryReminders(UUID userId) {
        docRepo.findByEmployeeUserIdOrderByUploadedAtDesc(userId).forEach(doc -> {
            if (doc.getExpiryDate() == null || !"VERIFIED".equals(doc.getStatus())) return;
            long days = ChronoUnit.DAYS.between(LocalDate.now(), doc.getExpiryDate());
            if (days < 0) return;
            for (int threshold : new int[]{60, 30, 7}) {
                if (days <= threshold) {
                    String linkPath = "/documents?expiry=" + doc.getId();
                    if (!notificationRepo.existsByUserIdAndLinkPath(userId, linkPath)) {
                        notificationService.send(userId, "DOCUMENT_EXPIRY",
                                "Document Expiring Soon",
                                doc.getDocumentType().getName() + " expires in " + days + " day(s). Please renew.",
                                linkPath);
                    }
                    break;
                }
            }
        });
    }

    private Map<UUID, String> nameMapFor(Set<UUID> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        return employeeRepo.findNamesByUserIds(ids).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1]));
    }

    private User requireUser(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + email));
    }

    private void requireAdminRole(String email) {
        User u = requireUser(email);
        boolean isAdmin = u.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
        if (!isAdmin) {
            throw new AccessDeniedException("Access denied");
        }
    }
}
