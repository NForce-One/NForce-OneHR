package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.expense.ExpenseClaimResponse;
import com.nforce.onehr.dto.expense.SubmitExpenseClaimRequest;
import com.nforce.onehr.entity.ExpenseCategory;
import com.nforce.onehr.entity.ExpenseClaim;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    @Mock private ExpenseClaimRepository claimRepo;
    @Mock private ExpenseCategoryRepository categoryRepo;
    @Mock private EmployeeManagerHistoryRepository historyRepo;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private NotificationService notificationService;
    @Mock private AttendanceProperties attendanceProperties;

    @InjectMocks
    private ExpenseService expenseService;

    private User employeeUser;
    private ExpenseCategory category;
    private final String actorEmail = "employee@test.com";

    @BeforeEach
    void setUp() {
        employeeUser = User.builder()
                .id(UUID.randomUUID())
                .email(actorEmail)
                .build();

        category = ExpenseCategory.builder()
                .id(1)
                .name("Travel")
                .requiresReceiptAbove(new BigDecimal("100.00"))
                .dailyLimit(new BigDecimal("1000.00"))
                .build();

        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
    }

    @Test
    void submit_futureExpenseDate_throwsIllegalArgumentException() {
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));

        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        SubmitExpenseClaimRequest req = new SubmitExpenseClaimRequest();
        req.setCategoryId(1);
        req.setAmount(new BigDecimal("50.00"));
        req.setExpenseDate(tomorrow);
        req.setBusinessPurpose("Client meeting in future");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> expenseService.submit(req, actorEmail));
        assertEquals("Expense date cannot be in the future", ex.getMessage());

        verify(claimRepo, never()).save(any());
    }

    @Test
    void submit_pastExpenseDate_succeeds() {
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));
        when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> {
            ExpenseClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        LocalDate pastDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(3);
        SubmitExpenseClaimRequest req = new SubmitExpenseClaimRequest();
        req.setCategoryId(1);
        req.setAmount(new BigDecimal("50.00"));
        req.setExpenseDate(pastDate);
        req.setBusinessPurpose("Past team lunch");

        ExpenseClaimResponse res = expenseService.submit(req, actorEmail);
        assertNotNull(res);
        assertEquals("SUBMITTED", res.getStatus());
        assertEquals(pastDate, res.getExpenseDate());
        verify(claimRepo, times(1)).save(any(ExpenseClaim.class));
    }

    @Test
    void submit_todayExpenseDate_succeeds() {
        when(userRepo.findByEmail(actorEmail)).thenReturn(Optional.of(employeeUser));
        when(categoryRepo.findById(1)).thenReturn(Optional.of(category));
        when(claimRepo.save(any(ExpenseClaim.class))).thenAnswer(inv -> {
            ExpenseClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        SubmitExpenseClaimRequest req = new SubmitExpenseClaimRequest();
        req.setCategoryId(1);
        req.setAmount(new BigDecimal("50.00"));
        req.setExpenseDate(today);
        req.setBusinessPurpose("Today team lunch");

        ExpenseClaimResponse res = expenseService.submit(req, actorEmail);
        assertNotNull(res);
        assertEquals("SUBMITTED", res.getStatus());
        assertEquals(today, res.getExpenseDate());
        verify(claimRepo, times(1)).save(any(ExpenseClaim.class));
    }
}
