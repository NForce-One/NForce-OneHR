package com.nforce.onehr.controller;

import com.nforce.onehr.dto.attendance.CreateWebClockInRequest;
import com.nforce.onehr.dto.attendance.PunchTimezoneRequest;
import com.nforce.onehr.dto.attendance.WebClockInResponse;
import com.nforce.onehr.service.WebClockInService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/attendance/web-clock-in")
@RequiredArgsConstructor
public class WebClockInController {

    private final WebClockInService webClockInService;

    @PostMapping
    public ResponseEntity<WebClockInResponse> submit(
            @Valid @RequestBody CreateWebClockInRequest req, Principal principal) {
        WebClockInResponse created = webClockInService.submit(req, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/mine")
    public List<WebClockInResponse> mine(Principal principal) {
        return webClockInService.listMine(principal.getName());
    }

    @PostMapping("/checkout")
    public WebClockInResponse checkOut(@RequestBody(required = false) PunchTimezoneRequest req, Principal principal) {
        return webClockInService.checkOut(principal.getName(), req != null ? req.getTimezone() : null);
    }

    /** Undoes today's still-open check-in (before check-out) — no approval needed, same as submit/checkout. */
    @DeleteMapping("/cancel")
    public ResponseEntity<Void> cancel(Principal principal) {
        webClockInService.cancel(principal.getName());
        return ResponseEntity.noContent().build();
    }
}
