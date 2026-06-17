package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AssetsTest {

    @Test
    void registeredAssetsHaveCorrectScale() {
        assertThat(Assets.USD.scale()).isEqualTo(2);
        assertThat(Assets.JPY.scale()).isZero();
        assertThat(Assets.BHD.scale()).isEqualTo(3);
        assertThat(Assets.ETH.scale()).isEqualTo(18);
        assertThat(Assets.USDC.scale()).isEqualTo(6);
    }

    @Test
    void lookupByCodeReturnsRegisteredAsset() {
        assertThat(Assets.of("USD")).isEqualTo(Assets.USD);
    }

    @Test
    void lookupOfUnknownAssetThrows() {
        assertThatThrownBy(() -> Assets.of("XYZ")).isInstanceOf(IllegalArgumentException.class);
    }
}
