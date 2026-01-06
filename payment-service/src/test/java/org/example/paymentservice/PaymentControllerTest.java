package org.example.paymentservice;

import org.common.payment.dto.RefundResponse;
import org.common.payment.enums.RefundStatus;
import org.example.paymentservice.controller.PaymentController;
import org.example.paymentservice.exception.RefundNotFoundException;
import org.example.paymentservice.service.PaymentService;
import org.example.paymentservice.service.RefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RefundService refundService;

    @MockBean
    private PaymentService paymentService;

    @Test
    @DisplayName("退款记录存在时，返回 202 和 RefundStatus")
    void testRefundCheck_Success() throws Exception {
        String refundNo = "R123456";
        Mockito.when(refundService.checkRefundStatus(refundNo))
                .thenReturn(RefundStatus.SUCCESS);

        mockMvc.perform(get("/payment/refund/check/{refundNo}", refundNo)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.refundNo").value(refundNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").isEmpty());
    }

    @Test
    @DisplayName("退款记录不存在时，返回 404 和 RefundNotFoundException 消息")
    void testRefundCheck_NotFound() throws Exception {
        String refundNo = "R_NOT_EXIST";

        Mockito.when(refundService.checkRefundStatus(anyString()))
                .thenThrow(new RefundNotFoundException("refund not exists", refundNo));

        mockMvc.perform(get("/payment/refund/check/{refundNo}", refundNo)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.refundNo").value(refundNo))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.message").value("refund not exists"));
    }
}
