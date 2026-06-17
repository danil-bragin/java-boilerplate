package com.acme.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemExceptionHandlerTest {

    enum TestError implements ErrorCode {
        ORDER_NOT_FOUND;

        @Override
        public String code() {
            return name();
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.NOT_FOUND;
        }

        @Override
        public String defaultTitle() {
            return "Order not found";
        }
    }

    @Test
    void mapsApiExceptionToProblemDetail() {
        var handler = new ProblemExceptionHandler();
        var ex = new ApiException(TestError.ORDER_NOT_FOUND, Map.of("orderId", "42"));

        ProblemDetail pd = handler.handleApiException(ex);

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getTitle()).isEqualTo("Order not found");
        assertThat(pd.getProperties()).containsEntry("code", "ORDER_NOT_FOUND");
        assertThat(pd.getProperties()).containsKey("params");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) pd.getProperties().get("params");
        assertThat(params).containsEntry("orderId", "42");
    }
}
