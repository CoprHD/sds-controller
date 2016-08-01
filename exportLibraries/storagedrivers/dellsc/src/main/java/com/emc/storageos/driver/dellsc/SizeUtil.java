/*
 * Copyright 2016 Dell Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.emc.storageos.driver.dellsc;

import java.math.BigDecimal;

/**
 * Utility functions for dealing with size.
 */
public class SizeUtil {

    /**
     * The number of bytes in a kilobyte.
     */
    public static final Long KB = 1024L;

    /**
     * The number of bytes in a megabyte.
     */
    public static final Long MB = 1024L * 1024L;

    /**
     * The number of bytes in a gigabyte.
     */
    public static final Long GB = 1024L * 1024L * 1024L;

    /**
     * Converts bytes to gigabytes.
     * 
     * @param bytes The byte size.
     * @return The size in gigs.
     */
    public static int byteToGig(long bytes) {
        return (int) (bytes / GB);
    }

    /**
     * Converts a size string from the API to bytes.
     * 
     * @param sizeStr The API size string.
     * @return The size in bytes.
     */
    public static Long sizeStrToBytes(String sizeStr) {
        String[] parts = sizeStr.split(" ");
        Long bytes = new BigDecimal(parts[0]).longValue();
        if (parts.length > 1) {
            if ("GB".equals(parts[1])) {
                bytes = bytes * GB;
            } else if ("MB".equals(parts[1])) {
                bytes = bytes * MB;
            } else if ("KB".equals(parts[1])) {
                bytes = bytes * KB;
            }
        }

        return bytes;
    }

    /**
     * Converts a speed string from the API to gigabits.
     *
     * @param speedStr The API speed string.
     * @return The speed in gigabits.
     */
    public static Long speedStrToGigabits(String speedStr) {
        if ("Unknown".equals(speedStr)) {
            return 0L;
        }

        String[] parts = speedStr.split(" ");
        Long gbits = new BigDecimal(parts[0]).longValue();
        if (parts.length > 1) {
            if ("Mbps".equals(parts[1])) {
                gbits = gbits / KB;
            }
        }

        return gbits;
    }
}
