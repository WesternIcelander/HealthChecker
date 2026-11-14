package io.siggi.healthchecker.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SmokeTest {
    @Test
    void apiPackageIsPresent() {
        assertNotNull(SmokeTest.class.getPackage());
        assertNotNull(SmokeTest.class.getPackageName());
    }
}
