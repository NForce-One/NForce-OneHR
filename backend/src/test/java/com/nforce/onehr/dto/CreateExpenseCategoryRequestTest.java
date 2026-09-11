package com.nforce.onehr.dto;

import com.nforce.onehr.dto.expense.CreateExpenseCategoryRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure jakarta.validation.Validator tests — no Spring context needed, same approach as
 * CreateHolidayRequestTest. Covers the name @Pattern added to block numeric-only, negative-number,
 * and symbol-only expense category names (mirrors CreateDepartmentRequest's identical regex).
 */
class CreateExpenseCategoryRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private CreateExpenseCategoryRequest request(String name) {
        CreateExpenseCategoryRequest req = new CreateExpenseCategoryRequest();
        req.setName(name);
        req.setRequiresReceiptAbove(BigDecimal.ZERO);
        return req;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123456", "-5", "-----", "@#$%^&*", "!!!", "   "
    })
    void rejectsNamesWithNoLetter(String invalidName) {
        Set<ConstraintViolation<CreateExpenseCategoryRequest>> violations = validator.validate(request(invalidName));
        assertFalse(violations.isEmpty(), "expected a violation for: " + invalidName);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Travel", "R&D Supplies", "Meals & Entertainment", "Office Supplies", "Client Gifts"
    })
    void allowsLegitimateCategoryNames(String validName) {
        Set<ConstraintViolation<CreateExpenseCategoryRequest>> violations = validator.validate(request(validName));
        assertTrue(violations.isEmpty(), "unexpected violations for: " + validName + " -> " + violations);
    }

    @Test
    void rejectsBlankName() {
        Set<ConstraintViolation<CreateExpenseCategoryRequest>> violations = validator.validate(request(""));
        assertFalse(violations.isEmpty());
    }

    @Test
    void rejectsNameOverMaxLength() {
        String tooLong = "a".repeat(61);
        Set<ConstraintViolation<CreateExpenseCategoryRequest>> violations = validator.validate(request(tooLong));
        assertFalse(violations.isEmpty());
    }

    @Test
    void allowsNameAtMaxLength() {
        String exactly60 = "a".repeat(60);
        Set<ConstraintViolation<CreateExpenseCategoryRequest>> violations = validator.validate(request(exactly60));
        assertTrue(violations.isEmpty());
    }
}
