package com.jimeng.dataserver.ai.feedback;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.feedback.service.FeedbackValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedbackValidatorTest {

    @Test
    void rejectsNonImage() {
        assertThrows(ServiceException.class,
                () -> FeedbackValidator.validateImage("application/pdf", 1024));
    }

    @Test
    void rejectsOversizeImage() {
        assertThrows(ServiceException.class,
                () -> FeedbackValidator.validateImage("image/png", 11L * 1024 * 1024));
    }

    @Test
    void acceptsValidImage() {
        assertDoesNotThrow(() -> FeedbackValidator.validateImage("image/png", 2048));
    }

    @Test
    void rejectsBadTypeOnSubmit() {
        assertThrows(ServiceException.class,
                () -> FeedbackValidator.validateSubmit(3, "x", 0));
    }

    @Test
    void rejectsEmptyContent() {
        assertThrows(ServiceException.class,
                () -> FeedbackValidator.validateSubmit(1, "  ", 0));
    }

    @Test
    void rejectsTooManyImages() {
        assertThrows(ServiceException.class,
                () -> FeedbackValidator.validateSubmit(1, "ok", 10));
    }

    @Test
    void acceptsValidSubmit() {
        assertDoesNotThrow(() -> FeedbackValidator.validateSubmit(2, "希望加暗色模式", 3));
    }
}
