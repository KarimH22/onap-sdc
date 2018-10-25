/*
 * Copyright © 2016-2018 European Support Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onap.config;

import static java.util.Optional.ofNullable;
import static org.onap.config.api.Hint.EXTERNAL_LOOKUP;
import static org.onap.config.api.Hint.LATEST_LOOKUP;
import static org.onap.config.api.Hint.NODE_SPECIFIC;

import com.virtlink.commons.configuration2.jackson.JsonConfiguration;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TransferQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.sf.corn.cps.CPScanner;
import net.sf.corn.cps.ResourceFilter;
import org.apache.commons.configuration2.CompositeConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.builder.BasicConfigurationBuilder;
import org.apache.commons.configuration2.builder.ReloadingFileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.io.IOUtils;
import org.onap.config.api.Config;
import org.onap.config.api.ConfigurationManager;
import org.onap.config.impl.AgglomerateConfiguration;
import org.onap.config.impl.ConfigurationRepository;
import org.onap.config.impl.YamlConfiguration;
import org.onap.config.type.ConfigurationMode;
import org.onap.config.type.ConfigurationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigurationUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationUtils.class);
    private static final String CONFIGURATION_TYPE_NOT_SUPPORTED = "Configuration type not supported:";
    private static final Map<Class, Class> ARRAY_CLASS_MAP;

    static {
        Map<Class, Class> arrayTypes = new HashMap<>();
        arrayTypes.put(Byte.class, Byte[].class);
        arrayTypes.put(Short.class, Short[].class);
        arrayTypes.put(Integer.class, Integer[].class);
        arrayTypes.put(Long.class, Long[].class);
        arrayTypes.put(Float.class, Float[].class);
        arrayTypes.put(Double.class, Double[].class);
        arrayTypes.put(Boolean.class, Boolean[].class);
        arrayTypes.put(Character.class, Character[].class);
        arrayTypes.put(String.class, String[].class);
        ARRAY_CLASS_MAP = Collections.unmodifiableMap(arrayTypes);
    }

    private ConfigurationUtils() {
    }

    public static ThreadFactory getThreadFactory() {
        return r1 -> {
            Thread thread = Executors.privilegedThreadFactory().newThread(r1);
            thread.setDaemon(true);
            return thread;
        };
    }

    public static Collection<File> getAllFiles(File file, boolean recursive, boolean onlyDirectory) {
        ArrayList<File> collection = new ArrayList<>();
        if (file.isDirectory() && file.exists()) {
            File[] files = file.listFiles();
            for (File innerFile : files) {
                if (innerFile.isFile() && !onlyDirectory) {
                    collection.add(innerFile);
                } else if (innerFile.isDirectory()) {
                    collection.add(innerFile);
                    if (recursive) {
                        collection.addAll(getAllFiles(innerFile, recursive, onlyDirectory));
                    }
                }
            }
        }
        return collection;
    }

    public static String getCommaSeparatedList(String[] list) {
        return getCommaSeparatedList(list == null ? Collections.emptyList() : Arrays.asList(list));
    }

    public static String getCommaSeparatedList(List list) {
        return ((Stream<String>) list.stream().filter(o -> o != null && !o.toString().trim().isEmpty())
                                         .map(o -> o.toString().trim())).collect(Collectors.joining(","));
    }

    public static boolean isConfig(URL url) {
        return isConfig(url.getFile());
    }

    public static boolean isConfig(String file) {
        file = file.toUpperCase().substring(file.lastIndexOf('!') + 1);
        file = file.substring(file.lastIndexOf('/') + 1);
        return file.matches(
                "CONFIG(-\\w*){0,1}(-" + "(" + ConfigurationMode.OVERRIDE + "|" + ConfigurationMode.MERGE + "|"
                        + ConfigurationMode.UNION + ")){0,1}" + "\\.(" + ConfigurationType.PROPERTIES.name() + "|"
                        + ConfigurationType.XML.name() + "|" + ConfigurationType.JSON.name() + "|"
                        + ConfigurationType.YAML.name() + ")$") || file.matches(
                "CONFIG(.)*\\.(" + ConfigurationType.PROPERTIES.name() + "|" + ConfigurationType.XML.name() + "|"
                        + ConfigurationType.JSON.name() + "|" + ConfigurationType.YAML.name() + ")$");
    }

    public static boolean isConfig(File file) {
        return file != null && file.exists() && isConfig(file.getName());
    }

    private static Optional<String> getNamespace(Configuration config) {
        return ofNullable(config).flatMap(configuration -> ofNullable(configuration.getString(Constants.NAMESPACE_KEY)))
                       .map(String::toUpperCase);
    }

    public static ConfigurationMode getMergeStrategy(URL url) {
        Optional<ConfigurationMode> configurationMode =
                getConfiguration(url).flatMap(ConfigurationUtils::getMergeStrategy)
                        .flatMap(ConfigurationUtils::convertConfigurationMode);
        return configurationMode.orElseGet(() -> getMergeStrategy(url.getFile().toUpperCase()));
    }

    public static Optional<FileBasedConfiguration> getConfiguration(URL url) {
        FileBasedConfiguration builder = null;
        try {
            ConfigurationType configType = ConfigurationUtils.getConfigType(url);
            switch (configType) {
                case PROPERTIES:
                    builder = new Configurations().fileBased(PropertiesConfiguration.class, url);
                    break;
                case XML:
                    builder = new Configurations().fileBased(XMLConfiguration.class, url);
                    break;
                case JSON:
                    builder = new Configurations().fileBased(JsonConfiguration.class, url);
                    break;
                case YAML:
                    builder = new Configurations().fileBased(YamlConfiguration.class, url);
                    break;
                default:
                    throw new ConfigurationException(CONFIGURATION_TYPE_NOT_SUPPORTED + configType);
            }
        } catch (ConfigurationException exception) {
            exception.printStackTrace();
        }
        return ofNullable(builder);
    }

    public static ConfigurationMode getMergeStrategy(String file) {
        file = file.toUpperCase().substring(file.lastIndexOf('!') + 1);
        file = file.substring(file.lastIndexOf('/') + 1);
        Pattern pattern = Pattern.compile(
                "CONFIG(-\\w*){0,1}(-" + "(" + ConfigurationMode.OVERRIDE + "|" + ConfigurationMode.MERGE + "|"
                        + ConfigurationMode.UNION + ")){0,1}" + "\\.(" + ConfigurationType.PROPERTIES.name() + "|"
                        + ConfigurationType.XML.name() + "|" + ConfigurationType.JSON.name() + "|"
                        + ConfigurationType.YAML.name() + ")$");
        Matcher matcher = pattern.matcher(file);
        boolean b1 = matcher.matches();
        if (b1) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String modeName = matcher.group(i);
                if (modeName != null) {
                    modeName = modeName.substring(1);
                }
                try {
                    return Enum.valueOf(ConfigurationMode.class, modeName);
                } catch (Exception exception) {
                    //do nothing
                }
            }
        }

        return null;
    }

    public static ConfigurationType getConfigType(URL url) {
        return Enum.valueOf(ConfigurationType.class,
                url.getFile().substring(url.getFile().lastIndexOf('.') + 1).toUpperCase());
    }

    private static Optional<ConfigurationMode> convertConfigurationMode(String configMode) {
        ConfigurationMode configurationMode = null;
        try {
            configurationMode = ConfigurationMode.valueOf(configMode);
        } catch (Exception exception) {
            LOGGER.error("Could not find convert {} into configuration mode", configMode);
        }
        return Optional.ofNullable(configurationMode);
    }

    private static Optional<String> getMergeStrategy(Configuration config) {
        return ofNullable(config).flatMap(configuration -> ofNullable(configuration.getString(Constants.MODE_KEY)))
                       .map(String::toUpperCase);
    }

    public static ConfigurationMode getMergeStrategy(File file) {
        Optional<ConfigurationMode> configurationMode =
                getConfiguration(file).flatMap(ConfigurationUtils::getMergeStrategy)
                        .flatMap(ConfigurationUtils::convertConfigurationMode);
        return configurationMode.orElseGet(() -> getMergeStrategy(file.getName().toUpperCase()));
    }

    public static Optional<FileBasedConfiguration> getConfiguration(File file) {
        FileBasedConfiguration builder = null;
        try {
            ConfigurationType configType = ConfigurationUtils.getConfigType(file);
            switch (configType) {
                case PROPERTIES:
                    builder = new Configurations().fileBased(PropertiesConfiguration.class, file);
                    break;
                case XML:
                    builder = new Configurations().fileBased(XMLConfiguration.class, file);
                    break;
                case JSON:
                    builder = new Configurations().fileBased(JsonConfiguration.class, file);
                    break;
                case YAML:
                    builder = new Configurations().fileBased(YamlConfiguration.class, file);
                    break;
                default:
                    throw new ConfigurationException(CONFIGURATION_TYPE_NOT_SUPPORTED + configType);
            }
        } catch (ConfigurationException exception) {
            exception.printStackTrace();
        }
        return ofNullable(builder);
    }

    public static ConfigurationType getConfigType(File file) {
        return Enum.valueOf(ConfigurationType.class,
                file.getAbsolutePath().substring(file.getAbsolutePath().lastIndexOf('.') + 1).toUpperCase());
    }

    public static Class getCollectionGenericType(Field field) {
        Type type = field.getGenericType();

        if (type instanceof ParameterizedType) {

            ParameterizedType paramType = (ParameterizedType) type;
            Type[] arr = paramType.getActualTypeArguments();
            if (arr.length > 0) {
                Class<?> clazz = (Class<?>) arr[0];
                if (isWrapperClass(clazz)) {
                    return clazz;
                } else {
                    throw new RuntimeException("Collection of type " + clazz.getName() + " not supported.");
                }
            }
        }

        return String[].class;
    }

    public static boolean isWrapperClass(Class clazz) {
        return clazz == String.class || clazz == Boolean.class || clazz == Character.class
                       || Number.class.isAssignableFrom(clazz);
    }

    public static Class getArrayClass(Class clazz) {
        return ARRAY_CLASS_MAP.getOrDefault(clazz, null);
    }

    public static List<URL> getAllClassPathResources() {
        return CPScanner.scanResources(new ResourceFilter());
    }

    public static BasicConfigurationBuilder<FileBasedConfiguration> getConfigurationBuilder(URL url) {
        ConfigurationType configType = ConfigurationUtils.getConfigType(url);
        ReloadingFileBasedConfigurationBuilder<FileBasedConfiguration> builder =
                getFileBasedConfigurationReloadingFileBasedConfigurationBuilder(configType);
        builder.configure(
                new Parameters().fileBased().setURL(url).setListDelimiterHandler(new DefaultListDelimiterHandler(',')));
        return builder;
    }

    private static ReloadingFileBasedConfigurationBuilder<FileBasedConfiguration>
            getFileBasedConfigurationReloadingFileBasedConfigurationBuilder(ConfigurationType configType) {

        ReloadingFileBasedConfigurationBuilder<FileBasedConfiguration> builder;
        switch (configType) {
            case PROPERTIES:
                builder = new ReloadingFileBasedConfigurationBuilder<>(PropertiesConfiguration.class);
                break;
            case XML:
                builder = new ReloadingFileBasedConfigurationBuilder<>(XMLConfiguration.class);
                break;
            case JSON:
                builder = new ReloadingFileBasedConfigurationBuilder<>(JsonConfiguration.class);
                break;
            case YAML:
                builder = new ReloadingFileBasedConfigurationBuilder<>(YamlConfiguration.class);
                break;
            default:
                throw new IllegalArgumentException(CONFIGURATION_TYPE_NOT_SUPPORTED + configType);
        }
        return builder;
    }

    public static BasicConfigurationBuilder<FileBasedConfiguration> getConfigurationBuilder(File file,
            boolean autoSave) {
        ReloadingFileBasedConfigurationBuilder<FileBasedConfiguration> builder;
        ConfigurationType configType = ConfigurationUtils.getConfigType(file);
        builder = getFileBasedConfigurationReloadingFileBasedConfigurationBuilder(configType);
        builder.configure(new Parameters().fileBased().setFile(file)
                                  .setListDelimiterHandler(new DefaultListDelimiterHandler(',')));
        builder.setAutoSave(autoSave);
        return builder;
    }

    public static <T> T read(Configuration config, Class<T> clazz, String keyPrefix) throws Exception {
        Config confAnnotation = clazz.getAnnotation(Config.class);
        if (confAnnotation != null) {
            keyPrefix += (confAnnotation.key() + ".");
        }
        T objToReturn = clazz.newInstance();
        for (Field field : clazz.getDeclaredFields()) {
            Config fieldAnnotation = field.getAnnotation(Config.class);
            if (fieldAnnotation != null) {
                field.setAccessible(true);
                field.set(objToReturn, config.getProperty(keyPrefix + fieldAnnotation.key()));
            } else if (field.getType().getAnnotation(Config.class) != null) {
                field.set(objToReturn, read(config, field.getType(), keyPrefix));
            }
        }
        return objToReturn;
    }

    public static Object getPrimitiveArray(Collection collection, Class clazz) {
        if (clazz == int.class) {
            int[] array = new int[collection.size()];
            Object[] objArray = collection.toArray();
            for (int i = 0; i < collection.size(); i++) {
                array[i] = (int) objArray[i];
            }
            return array;
        }
        if (clazz == byte.class) {
            byte[] array = new byte[collection.size()];
            Object[] objArray = collection.toArray();
            for (int i = 0; i < collection.size(); i++) {
                array[i] = (byte) objArray[i];
            }
            return array;
        }
        if (clazz == short.class) {
            short[] array = new short[collection.size()];
            Object[] objArray = collection.toArray();
            for (int i = 0; i < collection.size(); i++) {
                array[i] = (short) objArray[i];
            }
            return array;
        }
        if (clazz == long.class) {
            long[] array = new long[collection.size()];
            Object[] objArray = collection.toArray();
            for (int i = 0; i < collection.size(); i++) {
                array[i] = (long) objArray[i];
            }
            return array;
        }
        if (clazz == float.class) {
            float[] array = new float[collection.size()];
            Object[] objArray = collection.toArray();
            for (int i = 0; i < collection.size(); i++) {
                array[i] = (float) objArray[i];
            }
            return array;
        }
        if (clazz == double.class) {
            double[] array = new double[collection.size()];
            Object[] objArray = collection.toArray();
            for (int i = 0; i < collection.size(); i++) {
                array[i] = (double) objArray[i];
            }
            return array;
        }
        if (clazz == boolean.class) {
            boolean[] array = new boolean[collection.size()];
            Object[] objArray = collection.toArray();
            for (int i = 0; i < collection.size(); i++) {
                array[i] = (boolean) objArray[i];
            }
            return array;
        }
        return null;
    }

    public static String getCollectionString(String input) {
        Pattern pattern = Pattern.compile("^\\[(.*)\\]$");
        Matcher matcher = pattern.matcher(input);
        if (matcher.matches()) {
            input = matcher.group(1);
        }
        return input;
    }

    public static String processVariablesIfPresent(String tenant, String namespace, String data) {
        Pattern pattern = Pattern.compile("^.*\\$\\{(.*)\\}.*");
        Matcher matcher = pattern.matcher(data);
        if (matcher.matches()) {
            String key = matcher.group(1);
            if (key.toUpperCase().startsWith("ENV:")) {
                String envValue = System.getenv(key.substring(4));
                return processVariablesIfPresent(tenant, namespace, data.replaceAll("\\$\\{" + key + "\\}",
                        envValue == null ? "" : envValue.replace("\\", "\\\\")));
            } else if (key.toUpperCase().startsWith("SYS:")) {
                String sysValue = System.getProperty(key.substring(4));
                return processVariablesIfPresent(tenant, namespace, data.replaceAll("\\$\\{" + key + "\\}",
                        sysValue == null ? "" : sysValue.replace("\\", "\\\\")));
            } else {
                String propertyValue = ConfigurationUtils.getCollectionString(
                        ConfigurationManager.lookup().getAsStringValues(tenant, namespace, key).toString());
                return processVariablesIfPresent(tenant, namespace, data.replaceAll("\\$\\{" + key + "\\}",
                        propertyValue == null ? "" : propertyValue.replace("\\", "\\\\")));
            }
        } else {
            return data;
        }
    }

    public static String getFileContents(String path) {
        try {
            if (path != null) {
                return IOUtils.toString(new URL(path));
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return null;
    }

    public static String getFileContents(Path path) {
        try {
            if (path != null) {
                return new String(Files.readAllBytes(path));
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return null;
    }

    public static Object getDefaultFor(Class clazz) {
        if (byte.class == clazz) {
            return new Byte("0");
        } else if (short.class == clazz) {
            return new Short("0");
        } else if (int.class == clazz) {
            return new Integer("0");
        } else if (float.class == clazz) {
            return new Float("0");
        } else if (long.class == clazz) {
            return new Long("0");
        } else if (double.class == clazz) {
            return new Double("0");
        } else if (boolean.class == clazz) {
            return Boolean.FALSE;
        }
        return (char) 0;
    }

    public static Collection getCompatibleCollectionForAbstractDef(Class clazz) {
        if (BlockingQueue.class.isAssignableFrom(clazz)) {
            return getConcreteCollection(BlockingQueue.class);
        }
        if (TransferQueue.class.isAssignableFrom(clazz)) {
            return getConcreteCollection(TransferQueue.class);
        }
        if (Deque.class.isAssignableFrom(clazz)) {
            return getConcreteCollection(Deque.class);
        }
        if (Queue.class.isAssignableFrom(clazz)) {
            return getConcreteCollection(Queue.class);
        }
        if (SortedSet.class.isAssignableFrom(clazz)) {
            return getConcreteCollection(SortedSet.class);
        }
        if (Set.class.isAssignableFrom(clazz)) {
            return getConcreteCollection(Set.class);
        }
        if (List.class.isAssignableFrom(clazz)) {
            return getConcreteCollection(List.class);
        }
        return null;
    }

    public static Collection getConcreteCollection(Class clazz) {
        switch (clazz.getName()) {
            case "java.util.Collection":
            case "java.util.List":
                return new ArrayList<>();
            case "java.util.Set":
                return new HashSet<>();
            case "java.util.SortedSet":
                return new TreeSet<>();
            case "java.util.Queue":
                return new ConcurrentLinkedQueue<>();
            case "java.util.Deque":
                return new ArrayDeque<>();
            case "java.util.concurrent.TransferQueue":
                return new LinkedTransferQueue<>();
            case "java.util.concurrent.BlockingQueue":
                return new LinkedBlockingQueue<>();
            default:
                return null;
        }
    }

    public static String getConfigurationRepositoryKey(File file) {
        return getConfigurationRepositoryKey(
                ConfigurationUtils.getNamespace(file).split(Constants.TENANT_NAMESPACE_SEPARATOR));
    }

    public static String getConfigurationRepositoryKey(String[] array) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(Constants.DEFAULT_TENANT);
        for (String element : array) {
            stack.push(element);
        }
        String toReturn = stack.pop();
        return stack.pop() + Constants.KEY_ELEMENTS_DELIMETER + toReturn;
    }

    public static String getNamespace(File file) {
        Optional<String> namespace =
                getConfiguration(file).flatMap(ConfigurationUtils::getNamespace).map(String::toUpperCase);
        return namespace.orElseGet(() -> getNamespace(file.getName().toUpperCase()));
    }

    public static String getNamespace(String file) {
        file = file.toUpperCase().substring(file.lastIndexOf('!') + 1);
        file = file.substring(file.lastIndexOf('/') + 1);
        Pattern pattern = Pattern.compile(
                "CONFIG(-\\w*){0,1}(-" + "(" + ConfigurationMode.OVERRIDE + "|" + ConfigurationMode.MERGE + "|"
                        + ConfigurationMode.UNION + ")){0,1}" + "\\.(" + ConfigurationType.PROPERTIES.name() + "|"
                        + ConfigurationType.XML.name() + "|" + ConfigurationType.JSON.name() + "|"
                        + ConfigurationType.YAML.name() + ")$");
        Matcher matcher = pattern.matcher(file);
        boolean b1 = matcher.matches();
        if (b1) {
            if (matcher.group(1) != null) {
                String moduleName = matcher.group(1).substring(1);
                return moduleName.equalsIgnoreCase(ConfigurationMode.OVERRIDE.name()) || moduleName.equalsIgnoreCase(
                        ConfigurationMode.UNION.name()) || moduleName.equalsIgnoreCase(ConfigurationMode.MERGE.name())
                               ? Constants.DEFAULT_NAMESPACE : moduleName;
            } else {
                return Constants.DEFAULT_NAMESPACE;
            }
        } else if (isConfig(file)) {
            return Constants.DEFAULT_NAMESPACE;
        }

        return null;
    }

    public static String getConfigurationRepositoryKey(URL url) {
        return getConfigurationRepositoryKey(
                ConfigurationUtils.getNamespace(url).split(Constants.TENANT_NAMESPACE_SEPARATOR));
    }

    public static String getNamespace(URL url) {

        Optional<String> namespace =
                getConfiguration(url).flatMap(ConfigurationUtils::getNamespace).map(String::toUpperCase);

        return namespace.orElseGet(() -> getNamespace(url.getFile().toUpperCase()));
    }

    public static LinkedHashMap toMap(Configuration config) {
        Iterator<String> iterator = config.getKeys();
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (!(key.equals(Constants.MODE_KEY) || key.equals(Constants.NAMESPACE_KEY) || key.equals(
                    Constants.LOAD_ORDER_KEY))) {
                map.put(key, config.getProperty(key).toString());
            }
        }

        return map;
    }

    public static Map diff(LinkedHashMap orig, LinkedHashMap latest) {
        orig = new LinkedHashMap<>(orig);
        latest = new LinkedHashMap<>(latest);
        List<String> set = new ArrayList(orig.keySet());
        for (String key : set) {
            if (latest.remove(key, orig.get(key))) {
                orig.remove(key);
            }
        }
        Set<String> keys = latest.keySet();
        for (String key : keys) {
            orig.remove(key);
        }
        set = new ArrayList(orig.keySet());
        for (String key : set) {
            latest.put(key, "");
        }
        return new HashMap<>(latest);
    }

    public static boolean isArray(String tenant, String namespace, String key, int processingHints) throws Exception {
        Object obj = ConfigurationUtils
                             .getProperty(ConfigurationRepository.lookup().getConfigurationFor(tenant, namespace), key,
                                     processingHints);
        return (obj != null) && ConfigurationUtils.isCollection(obj.toString());
    }

    public static Object getProperty(Configuration config, String key, int processingHints) {
        if (!isDirectLookup(processingHints)) {
            if (config instanceof AgglomerateConfiguration) {
                return ((AgglomerateConfiguration) config).getPropertyValue(key);
            } else if (config instanceof CompositeConfiguration) {
                CompositeConfiguration conf = (CompositeConfiguration) config;
                for (int i = 0; i < conf.getNumberOfConfigurations(); i++) {
                    if (conf.getConfiguration(i) instanceof AgglomerateConfiguration) {
                        return ((AgglomerateConfiguration) conf.getConfiguration(i)).getPropertyValue(key);
                    } else if (isNodeSpecific(processingHints)) {
                        Object obj = conf.getConfiguration(i).getProperty(key);
                        if (obj != null) {
                            return obj;
                        }
                    }
                }
            }
        }
        return config.getProperty(key);
    }

    public static boolean isCollection(String input) {
        Pattern pattern = Pattern.compile("^\\[(.*)\\]$");
        Matcher matcher = pattern.matcher(input);
        return matcher.matches();
    }

    public static boolean isDirectLookup(int hints) {
        return (hints & LATEST_LOOKUP.value()) == LATEST_LOOKUP.value();
    }

    public static boolean isNodeSpecific(int hints) {
        return (hints & NODE_SPECIFIC.value()) == NODE_SPECIFIC.value();
    }

    public static boolean isExternalLookup(int hints) {
        return (hints & EXTERNAL_LOOKUP.value()) == EXTERNAL_LOOKUP.value();
    }

    public static boolean isZeroLengthArray(Class clazz, Object obj) {
        if (clazz.isArray() && clazz.getComponentType().isPrimitive()) {
            if (clazz.getComponentType() == int.class) {
                return ((int[]) obj).length == 0;
            } else if (clazz.getComponentType() == byte.class) {
                return ((byte[]) obj).length == 0;
            } else if (clazz.getComponentType() == short.class) {
                return ((short[]) obj).length == 0;
            } else if (clazz.getComponentType() == float.class) {
                return ((float[]) obj).length == 0;
            } else if (clazz.getComponentType() == boolean.class) {
                return ((boolean[]) obj).length == 0;
            } else if (clazz.getComponentType() == double.class) {
                return ((double[]) obj).length == 0;
            } else if (clazz.getComponentType() == long.class) {
                return ((long[]) obj).length == 0;
            } else {
                return ((Object[]) obj).length == 0;
            }
        }

        return false;
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }
}
