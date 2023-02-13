package com.alibaba.fastjson2.writer;

import com.alibaba.fastjson2.codec.FieldInfo;
import com.alibaba.fastjson2.function.ToByteFunction;
import com.alibaba.fastjson2.function.ToFloatFunction;
import com.alibaba.fastjson2.function.ToShortFunction;
import com.alibaba.fastjson2.util.ParameterizedTypeImpl;
import com.alibaba.fastjson2.util.TypeUtils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.*;

public class ObjectWriters {
    static ObjectWriterCreator INSTANCE = ObjectWriterCreator.INSTANCE;

    public static ObjectWriter ofReflect(Class objectType) {
        return ObjectWriterCreator.INSTANCE.createObjectWriter(objectType);
    }

    public static ObjectWriter objectWriter(Class objectType) {
        return INSTANCE.createObjectWriter(objectType);
    }

    public static ObjectWriter objectWriter(Class objectType, FieldWriter... fieldWriters) {
        return INSTANCE.createObjectWriter(objectType, fieldWriters);
    }

    public static <T> ObjectWriter<T> of(Class<T> objectType, FieldWriter... fieldWriters) {
        return INSTANCE.createObjectWriter(objectType, fieldWriters);
    }

    public static ObjectWriter objectWriter(Class objectType, long features, FieldWriter... fieldWriters) {
        return INSTANCE.createObjectWriter(objectType, features, fieldWriters);
    }

    public static ObjectWriter objectWriter(FieldWriter... fieldWriters) {
        return INSTANCE.createObjectWriter(fieldWriters);
    }

    public static <T> ObjectWriter ofToString(Function<T, String> function) {
        return INSTANCE.createObjectWriter(
                INSTANCE.createFieldWriter(
                        null,
                        null,
                        "toString",
                        0,
                        FieldInfo.VALUE_MASK,
                        null,
                        null,
                        String.class,
                        String.class,
                        null,
                        function
                )
        );
    }

    public static <T> ObjectWriter ofToInt(ToIntFunction function) {
        return INSTANCE.createObjectWriter(
                new FieldWriterInt32ValFunc(
                        "toInt",
                        0,
                        FieldInfo.VALUE_MASK,
                        null,
                        null,
                        null,
                        function
                )
        );
    }

    public static <T> ObjectWriter ofToLong(ToLongFunction function) {
        return INSTANCE.createObjectWriter(
                new FieldWriterInt64ValFunc(
                        "toLong",
                        0,
                        FieldInfo.VALUE_MASK,
                        null,
                        null,
                        null,
                        function
                )
        );
    }

    public static <T> FieldWriter fieldWriter(String fieldName, ToLongFunction<T> function) {
        return INSTANCE.createFieldWriter(fieldName, function);
    }

    public static <T> FieldWriter fieldWriter(String fieldName, ToIntFunction<T> function) {
        return INSTANCE.createFieldWriter(fieldName, function);
    }

    public static <T> FieldWriter fieldWriter(String fieldName, ToShortFunction<T> function) {
        return INSTANCE.createFieldWriter(fieldName, function);
    }

    public static <T> FieldWriter fieldWriter(String fieldName, ToByteFunction<T> function) {
        return INSTANCE.createFieldWriter(fieldName, function);
    }

    public static <T> FieldWriter fieldWriter(String fieldName, ToFloatFunction<T> function) {
        return INSTANCE.createFieldWriter(fieldName, function);
    }

    public static <T> FieldWriter fieldWriter(String fieldName, ToDoubleFunction<T> function) {
        return INSTANCE.createFieldWriter(fieldName, function);
    }

    public static <T> FieldWriter fieldWriter(String fieldName, Predicate<T> function) {
        return INSTANCE.createFieldWriter(fieldName, function);
    }

    public static <T> FieldWriter fieldWriter(String fieldName, Function<T, String> function) {
        return INSTANCE.createFieldWriter(fieldName, String.class, function);
    }

    public static <T, V> FieldWriter fieldWriter(String fieldName, Class<V> fieldClass, Function<T, V> function) {
        return INSTANCE.createFieldWriter(fieldName, fieldClass, function);
    }

    public static <T, V> FieldWriter fieldWriter(String fieldName, Type fieldType, Class<V> fieldClass, Function<T, V> function) {
        return INSTANCE.createFieldWriter(fieldName, fieldType, fieldClass, function);
    }

    public static <T, V> FieldWriter fieldWriterList(String fieldName, Class<V> itemType, Function<T, List<V>> function) {
        ParameterizedType listType;
        if (itemType == String.class) {
            listType = TypeUtils.PARAM_TYPE_LIST_STR;
        } else {
            listType = new ParameterizedTypeImpl(List.class, itemType);
        }
        return INSTANCE.createFieldWriter(fieldName, listType, List.class, function);
    }

    public static <T> FieldWriter fieldWriterListString(String fieldName, Function<T, List<String>> function) {
        return INSTANCE.createFieldWriter(fieldName, TypeUtils.PARAM_TYPE_LIST_STR, List.class, function);
    }
}
