package com.sekwah.advancedportals.core.serializeddata;

import com.google.inject.Inject;
import com.sekwah.advancedportals.core.util.InfoLogger;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.*;
// import sun.misc.Unsafe; // Removed due to access restrictions

public class ReflectiveConstructor<T> extends Constructor {
    // private static final Unsafe unsafe = getUnsafe(); // Removed due to access restrictions
    private final Class<T> clazz;

    @Inject
    private InfoLogger infoLogger;

    public ReflectiveConstructor(Class<T> clazz, LoaderOptions loadingConfig) {
        super(clazz, loadingConfig);
        this.clazz = clazz;
    }

    @Override
    protected Object constructObject(Node node) {
        return this.constructObject(clazz, node);
    }

    private Object constructObject(Class<?> currentClass, Node node) {
        if (node instanceof MappingNode) {
            return constructFromMappingNode(currentClass, (MappingNode) node);
        } else if (node instanceof ScalarNode) {
            return constructFromScalarNode((ScalarNode) node);
        } else if (node instanceof SequenceNode) {
            return constructFromSequenceNode((SequenceNode) node);
        } else {
            infoLogger.warning("Unexpected node type encountered: "
                               + node.getClass().getSimpleName());
            return null;
        }
    }

    private Object constructFromSequenceNode(SequenceNode sequenceNode) {
        List<?> list = (List<?>) super.constructObject(sequenceNode);

        if (list == null || list.isEmpty()) {
            return list;
        }

        Class<?> componentType = list.get(0).getClass();
        Object array = Array.newInstance(componentType, list.size());
        for (int i = 0; i < list.size(); i++) {
            Array.set(array, i, list.get(i));
        }

        return array;
    }

    private <U> Object constructFromMappingNode(Class<U> currentClass,
                                                MappingNode mappingNode) {
        // if the class is a hashmap, loop over the values and construct the
        // objects
        if (currentClass.equals(HashMap.class)) {
            Map<String, Object> values = new HashMap<>();
            for (NodeTuple tuple : mappingNode.getValue()) {
                String key = (String) constructObject(tuple.getKeyNode());

                Node node = tuple.getValueNode();

                if (node instanceof ScalarNode) {
                    Object constructedItem =
                        constructFromScalarNode((ScalarNode) node);
                    values.put(key, constructedItem);
                } else if (node instanceof SequenceNode) {
                    SequenceNode sequenceNode = (SequenceNode) node;
                    Object constructedItem =
                        constructFromSequenceNode(sequenceNode);
                    values.put(key, constructedItem);
                } else if (node instanceof MappingNode) {
                    MappingNode mappingNodeChild = (MappingNode) node;
                    try {
                        Object value = constructFromMappingNode(
                            Object.class, mappingNodeChild);
                        values.put(key, value);
                    } catch (Exception e) {
                        infoLogger.warning(
                            "Failed to construct object from mapping node: "
                            + e.getMessage());
                    }
                } else {
                    infoLogger.warning("Unexpected node type encountered: "
                                       + node.getClass().getSimpleName());
                }
            }
            return values;
        }

        try {
            Object instance;
            try {
                instance = currentClass.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException e) {
                infoLogger.info("No default constructor found for "
                                + currentClass.getName()
                                + ", using unsafe allocation.");
                instance = createInstanceWithoutConstructor(currentClass);
            }

            Map<String, Object> mappedValues =
                mapMappingNode(currentClass, mappingNode);

            Field[] fields = getAllFields(currentClass);
            for (Field field : fields) {
                if (Modifier.isTransient(field.getModifiers())) {
                    continue;
                }

                try {
                    if (mappedValues.containsKey(field.getName())) {
                        Object value = mappedValues.get(field.getName());

                        setField(instance, field, value);
                    } else {
                        infoLogger.warning("Field " + field.getName()
                                           + " not found in mapping node "
                                           + instance.getClass().getName()
                                           + " will use default value.");
                    }
                } catch (Exception e) {
                    infoLogger.warning("Failed to set field " + field.getName()
                                       + " in " + currentClass.getName() + ": "
                                       + e.getMessage());
                    infoLogger.error(e);
                    throw new RuntimeException("Failed to set field "
                                                   + field.getName() + " in "
                                                   + currentClass.getName(),
                                               e);
                }
            }
            return instance;
        } catch (Exception e) {
            infoLogger.warning("Failed to instantiate " + currentClass.getName()
                               + ": " + e.getMessage());
            throw new RuntimeException(
                "Failed to instantiate " + currentClass.getName(), e);
        }
    }

    private Field[] getAllFields(Class<?> clazz) {
        Map<String, Field> fields = new HashMap<>();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                fields.putIfAbsent(field.getName(), field);
            }
            clazz = clazz.getSuperclass();
        }
        return fields.values().toArray(new Field[0]);
    }

    private Object constructFromScalarNode(ScalarNode scalarNode) {
        return super.constructObject(scalarNode);
    }

    private Map<String, Object> mapMappingNode(Class<?> currentClass,
                                               MappingNode mappingNode) {
        Map<String, Object> values = new HashMap<>();
        for (NodeTuple tuple : mappingNode.getValue()) {
            String key = (String) super.constructObject(tuple.getKeyNode());

            Node node = tuple.getValueNode();

            if (node instanceof ScalarNode) {
                ScalarNode scalarNode = (ScalarNode) node;
                values.put(key, constructFromScalarNode(scalarNode));
            } else if (node instanceof MappingNode) {
                MappingNode mappingNodeChild = (MappingNode) node;
                try {
                    Field field = currentClass.getDeclaredField(key);
                    Object value = constructFromMappingNode(field.getType(),
                                                            mappingNodeChild);
                    values.put(key, value);
                } catch (NoSuchFieldException e) {
                    infoLogger.warning("Field " + key + " not found on "
                                       + currentClass.getName());
                }
            } else {
                infoLogger.warning("Expected mapping node: "
                                   + node.getClass().getSimpleName());
            }
        }
        return values;
    }

    /**
     * Check and convert value types e.g. double to float
     */
    private void setField(Object instance, Field field, Object value)
        throws IllegalAccessException {
        // Check for numeric type compatibility and cast if necessary
        if (field.getType() == float.class &&value instanceof Double) {
            value = ((Double) value).floatValue();
        } else if (field.getType() == int.class &&value instanceof Long) {
            value = ((Long) value).intValue();
        } else if (field.getType() == short.class &&value instanceof Integer) {
            value = ((Integer) value).shortValue();
        } else if (field.getType() == byte.class && value instanceof Integer) {
            value = ((Integer) value).byteValue();
        }

        field.setAccessible(true);
        field.set(instance, value);
    }

    private static Object createInstanceWithoutConstructor(Class<?> clazz) {
        try {
            java.lang.reflect.Constructor<?> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
        }
    }
}
