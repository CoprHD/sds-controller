package com.emc.vipr.sanity

import org.junit.Test

import com.emc.vipr.sanity.catalog.BlockServicesHelper

public class CatalogBlockServicesSanity {

    @Test void createBlockVolumeTest() {
        BlockServicesHelper.createAndRemoveBlockVolumeTest()
    }

    @Test void createBlockVolumeForHostTest() {
        BlockServicesHelper.createAndRemoveBlockVolumeForHostTest()
    }
}
