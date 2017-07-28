/*
 * Copyright (c) 2017 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.driver.univmax.rest;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.lang.reflect.Type;

import static com.google.json.JsonSanitizer.sanitize;

public class JsonUtil {

    public static <T> T fromJson(String s, Class<T> clazz) {
        try {
            return (T) new Gson().fromJson(sanitize(s), clazz);
        } catch (JsonSyntaxException e) {
            throw new RuntimeException(s, e);
        }
    }

    public static <T> T fromJsonIter(String s, Type type) {
        return (T) new Gson().fromJson(sanitize(s), type);
    }

    public static String toJsonString(Object t) {
        return new Gson().toJson(t);
    }

}
