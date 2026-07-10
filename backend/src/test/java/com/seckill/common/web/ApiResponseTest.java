package com.seckill.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void okShouldReturnCodeZeroWithData() {
        ApiResponse<String> response = ApiResponse.ok("payload");

        assertThat(response.code()).isZero();
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.data()).isEqualTo("payload");
    }

    @Test
    void okWithoutDataShouldReturnNullData() {
        ApiResponse<Void> response = ApiResponse.ok();

        assertThat(response.code()).isZero();
        assertThat(response.data()).isNull();
    }

    @Test
    void errorShouldCarryCodeAndMessage() {
        ApiResponse<Void> response = ApiResponse.error(3001, "售罄");

        assertThat(response.code()).isEqualTo(3001);
        assertThat(response.message()).isEqualTo("售罄");
        assertThat(response.data()).isNull();
    }
}
