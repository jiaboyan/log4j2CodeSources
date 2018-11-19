/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.composite.CompositeConfiguration;
import org.apache.logging.log4j.core.config.json.JsonConfigurationFactory;
import org.apache.logging.log4j.core.config.plugins.util.PluginManager;
import org.apache.logging.log4j.core.config.plugins.util.PluginType;
import org.apache.logging.log4j.core.config.properties.PropertiesConfiguration;
import org.apache.logging.log4j.core.config.properties.PropertiesConfigurationFactory;
import org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory;
import org.apache.logging.log4j.core.config.yaml.YamlConfigurationFactory;
import org.apache.logging.log4j.core.lookup.Interpolator;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;
import org.apache.logging.log4j.core.util.FileUtils;
import org.apache.logging.log4j.core.util.Loader;
import org.apache.logging.log4j.core.util.NetUtils;
import org.apache.logging.log4j.core.util.ReflectionUtil;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.LoaderUtil;
import org.apache.logging.log4j.util.PropertiesUtil;
import org.apache.logging.log4j.util.Strings;

/**
 * Factory class for parsed {@link Configuration} objects from a configuration file.
 * ConfigurationFactory allows the configuration implementation to be
 * dynamically chosen in 1 of 3 ways:
 * <ol>
 * <li>A system property named "log4j.configurationFactory" can be set with the
 * name of the ConfigurationFactory to be used.</li>
 * <li>
 * {@linkplain #setConfigurationFactory(ConfigurationFactory)} can be called
 * with the instance of the ConfigurationFactory to be used. This must be called
 * before any other calls to Log4j.</li>
 * <li>
 * A ConfigurationFactory implementation can be added to the classpath and configured as a plugin in the
 * {@link #CATEGORY ConfigurationFactory} category. The {@link Order} annotation should be used to configure the
 * factory to be the first one inspected. See
 * {@linkplain org.apache.logging.log4j.core.config.xml.XmlConfigurationFactory} for an example.</li>
 * </ol>
 *
 * If the ConfigurationFactory that was added returns null on a call to
 * getConfiguration then any other ConfigurationFactories found as plugins will
 * be called in their respective order. DefaultConfiguration is always called
 * last if no configuration has been returned.
 */
public abstract class ConfigurationFactory extends ConfigurationBuilderFactory {

    public ConfigurationFactory() {
        super();
        // TEMP For breakpoints
    }

    /**
     * Allows the ConfigurationFactory class to be specified as a system property.
     */
    public static final String CONFIGURATION_FACTORY_PROPERTY = "log4j.configurationFactory";

    /**
     * Allows the location of the configuration file to be specified as a system property.
     */
    public static final String CONFIGURATION_FILE_PROPERTY = "log4j.configurationFile";

    /**
     * Plugin category used to inject a ConfigurationFactory {@link org.apache.logging.log4j.core.config.plugins.Plugin}
     * class.
     *
     * @since 2.1
     */
    public static final String CATEGORY = "ConfigurationFactory";

    /**
     * Allows subclasses access to the status logger without creating another instance.
     */
    protected static final Logger LOGGER = StatusLogger.getLogger();

    /**
     * File name prefix for test configurations.
     */
    protected static final String TEST_PREFIX = "log4j2-test";

    /**
     * File name prefix for standard configurations.
     */
    protected static final String DEFAULT_PREFIX = "log4j2";

    /**
     * The name of the classloader URI scheme.
     */
    private static final String CLASS_LOADER_SCHEME = "classloader";

    /**
     * The name of the classpath URI scheme, synonymous with the classloader URI scheme.
     */
    private static final String CLASS_PATH_SCHEME = "classpath";

    private static volatile List<ConfigurationFactory> factories = null;

    private static ConfigurationFactory configFactory = new Factory();

    protected final StrSubstitutor substitutor = new StrSubstitutor(new Interpolator());

    private static final Lock LOCK = new ReentrantLock();

    //获取配置工厂实例：具体的配置工厂
    public static ConfigurationFactory getInstance() {
        if (factories == null) {
            LOCK.lock();
            try {
                if (factories == null) {
                    final List<ConfigurationFactory> list = new ArrayList<>();
                    //配置文件log4j2.component.properties中获取：log4j.configurationFactory 为key的值：
                    final String factoryClass = PropertiesUtil.getProperties().getStringProperty(CONFIGURATION_FACTORY_PROPERTY);
                    //如果配置了，不为null，，将 配置工厂 放到list集合中：
                    if (factoryClass != null) {
                        addFactory(list, factoryClass);
                    }
//                    Log4j2的插件管理：
                    final PluginManager manager = new PluginManager(CATEGORY);
                    //查找所有的插件：
                    manager.collectPlugins();
                    //获取 Log4j2Plugin.dat 中的 配置工厂插件：
                    final Map<String, PluginType<?>> plugins = manager.getPlugins();
                    final List<Class<? extends ConfigurationFactory>> ordered = new ArrayList<>(plugins.size());
                    //遍历插件：
                    for (final PluginType<?> type : plugins.values()) {
                        try {
//                            将配置工厂创建Class对象，添加到集合中：
                            ordered.add(type.getPluginClass().asSubclass(ConfigurationFactory.class));
                        } catch (final Exception ex) {
                            LOGGER.warn("Unable to add class {}", type.getPluginClass(), ex);
                        }
                    }
                    Collections.sort(ordered, OrderComparator.getInstance());
                    //实例化配置工厂Class对象，添加list集合中：
                    for (final Class<? extends ConfigurationFactory> clazz : ordered) {
                        addFactory(list, clazz);
                    }
                    //给配置工厂集合赋值：
                    factories = Collections.unmodifiableList(list);
                }
            } finally {
                LOCK.unlock();
            }
        }

        LOGGER.debug("Using configurationFactory {}", configFactory);
        return configFactory;
    }

    private static void addFactory(final Collection<ConfigurationFactory> list, final String factoryClass) {
        try {
            addFactory(list, LoaderUtil.loadClass(factoryClass).asSubclass(ConfigurationFactory.class));
        } catch (final Exception ex) {
            LOGGER.error("Unable to load class {}", factoryClass, ex);
        }
    }

    private static void addFactory(final Collection<ConfigurationFactory> list,
                                   final Class<? extends ConfigurationFactory> factoryClass) {
        try {
            list.add(ReflectionUtil.instantiate(factoryClass));
        } catch (final Exception ex) {
            LOGGER.error("Unable to create instance of {}", factoryClass.getName(), ex);
        }
    }

    /**
     * Sets the configuration factory. This method is not intended for general use and may not be thread safe.
     * @param factory the ConfigurationFactory.
     */
    public static void setConfigurationFactory(final ConfigurationFactory factory) {
        configFactory = factory;
    }

    /**
     * Resets the ConfigurationFactory to the default. This method is not intended for general use and may
     * not be thread safe.
     */
    public static void resetConfigurationFactory() {
        configFactory = new Factory();
    }

    /**
     * Removes the ConfigurationFactory. This method is not intended for general use and may not be thread safe.
     * @param factory The factory to remove.
     */
    public static void removeConfigurationFactory(final ConfigurationFactory factory) {
        if (configFactory == factory) {
            configFactory = new Factory();
        }
    }

    protected abstract String[] getSupportedTypes();

    protected boolean isActive() {
        return true;
    }

    public abstract Configuration getConfiguration(final LoggerContext loggerContext, ConfigurationSource source);

    /**
     * Returns the Configuration.
     * @param loggerContext The logger context
     * @param name The configuration name.
     * @param configLocation The configuration location.
     * @return The Configuration.
     */
    public Configuration getConfiguration(final LoggerContext loggerContext, final String name, final URI configLocation) {
        if (!isActive()) {
            return null;
        }
        if (configLocation != null) {
            final ConfigurationSource source = getInputFromUri(configLocation);
            if (source != null) {
                return getConfiguration(loggerContext, source);
            }
        }
        return null;
    }

   //获取 配置 ：
    public Configuration getConfiguration(final LoggerContext loggerContext, final String name, final URI configLocation, final ClassLoader loader) {
        if (!isActive()) {
            return null;
        }
        //loader为null 进入getConfiguration方法，该方法 属于内部类Factory的方法：
        if (loader == null) {
            return getConfiguration(loggerContext, name, configLocation);
        }
        if (isClassLoaderUri(configLocation)) {
            final String path = extractClassLoaderUriPath(configLocation);
            final ConfigurationSource source = getInputFromResource(path, loader);
            if (source != null) {
                final Configuration configuration = getConfiguration(loggerContext, source);
                if (configuration != null) {
                    return configuration;
                }
            }
        }
        return getConfiguration(loggerContext, name, configLocation);
    }

    /**
     * Loads the configuration from a URI.
     * @param configLocation A URI representing the location of the configuration.
     * @return The ConfigurationSource for the configuration.
     */
    protected ConfigurationSource getInputFromUri(final URI configLocation) {
        final File configFile = FileUtils.fileFromUri(configLocation);
        if (configFile != null && configFile.exists() && configFile.canRead()) {
            try {
                return new ConfigurationSource(new FileInputStream(configFile), configFile);
            } catch (final FileNotFoundException ex) {
                LOGGER.error("Cannot locate file {}", configLocation.getPath(), ex);
            }
        }
        if (isClassLoaderUri(configLocation)) {
            final ClassLoader loader = LoaderUtil.getThreadContextClassLoader();
            final String path = extractClassLoaderUriPath(configLocation);
            final ConfigurationSource source = getInputFromResource(path, loader);
            if (source != null) {
                return source;
            }
        }
        if (!configLocation.isAbsolute()) { // LOG4J2-704 avoid confusing error message thrown by uri.toURL()
            LOGGER.error("File not found in file system or classpath: {}", configLocation.toString());
            return null;
        }
        try {
            return new ConfigurationSource(configLocation.toURL().openStream(), configLocation.toURL());
        } catch (final MalformedURLException ex) {
            LOGGER.error("Invalid URL {}", configLocation.toString(), ex);
        } catch (final Exception ex) {
            LOGGER.error("Unable to access {}", configLocation.toString(), ex);
        }
        return null;
    }

    private static boolean isClassLoaderUri(final URI uri) {
        if (uri == null) {
            return false;
        }
        final String scheme = uri.getScheme();
        return scheme == null || scheme.equals(CLASS_LOADER_SCHEME) || scheme.equals(CLASS_PATH_SCHEME);
    }

    private static String extractClassLoaderUriPath(final URI uri) {
        return uri.getScheme() == null ? uri.getPath() : uri.getSchemeSpecificPart();
    }

    /**
     * Loads the configuration from the location represented by the String.
     * @param config The configuration location.
     * @param loader The default ClassLoader to use.
     * @return The InputSource to use to read the configuration.
     */
    protected ConfigurationSource getInputFromString(final String config, final ClassLoader loader) {
        try {
            final URL url = new URL(config);
            return new ConfigurationSource(url.openStream(), FileUtils.fileFromUri(url.toURI()));
        } catch (final Exception ex) {
            final ConfigurationSource source = getInputFromResource(config, loader);
            if (source == null) {
                try {
                    final File file = new File(config);
                    return new ConfigurationSource(new FileInputStream(file), file);
                } catch (final FileNotFoundException fnfe) {
                    // Ignore the exception
                    LOGGER.catching(Level.DEBUG, fnfe);
                }
            }
            return source;
        }
    }

    /**
     * Retrieves the configuration via the ClassLoader.
     * @param resource The resource to load.
     * @param loader The default ClassLoader to use.
     * @return The ConfigurationSource for the configuration.
     */
    protected ConfigurationSource getInputFromResource(final String resource, final ClassLoader loader) {
        final URL url = Loader.getResource(resource, loader);
        if (url == null) {
            return null;
        }
        InputStream is = null;
        try {
            is = url.openStream();
        } catch (final IOException ioe) {
            LOGGER.catching(Level.DEBUG, ioe);
            return null;
        }
        if (is == null) {
            return null;
        }

        if (FileUtils.isFile(url)) {
            try {
                return new ConfigurationSource(is, FileUtils.fileFromUri(url.toURI()));
            } catch (final URISyntaxException ex) {
                // Just ignore the exception.
                LOGGER.catching(Level.DEBUG, ex);
            }
        }
        return new ConfigurationSource(is, url);
    }

    /**
     * Default Factory.
     */
    private static class Factory extends ConfigurationFactory {

        private static final String ALL_TYPES = "*";

        //获取配置对象：
        @Override
        public Configuration getConfiguration(final LoggerContext loggerContext, final String name, final URI configLocation) {

            if (configLocation == null) {
                //从配置文件log4j2.component.properties中获取 key为 log4j.configurationFile的值： 此处建议配置，提高效率
                final String configLocationStr = this.substitutor.replace(PropertiesUtil.getProperties()
                        .getStringProperty(CONFIGURATION_FILE_PROPERTY));
                if (configLocationStr != null) {
                    final String[] sources = configLocationStr.split(",");
                    if (sources.length > 1) {
                        final List<AbstractConfiguration> configs = new ArrayList<>();
                        for (final String sourceLocation : sources) {
                            final Configuration config = getConfiguration(loggerContext, sourceLocation.trim());
                            if (config != null && config instanceof AbstractConfiguration) {
                                configs.add((AbstractConfiguration) config);
                            } else {
                                LOGGER.error("Failed to created configuration at {}", sourceLocation);
                                return null;
                            }
                        }
                        return new CompositeConfiguration(configs);
                    }
                    return getConfiguration(loggerContext, configLocationStr);
                }
                //如果没有配置log4j2.component.properties的key的话，就使用配置工厂去获取：
                for (final ConfigurationFactory factory : getFactories()) {
//                    PropertiesConfigurationFactory、
//                    YamlConfigurationFactory
//                    JsonConfigurationFactory
//                    XmlConfigurationFactory
//                    看这个这些配置工厂支持的文件类型：
                    final String[] types = factory.getSupportedTypes();
                    if (types != null) {
                        for (final String type : types) {
                            //看这鞋配置工厂支持的类型 是否有 * ：
                            if (type.equals(ALL_TYPES)) {
                                final Configuration config = factory.getConfiguration(loggerContext, name, configLocation);
                                if (config != null) {
                                    return config;
                                }
                            }
                        }
                    }
                }
            } else {
                final String configLocationStr = configLocation.toString();
                for (final ConfigurationFactory factory : getFactories()) {
                    final String[] types = factory.getSupportedTypes();
                    if (types != null) {
                        for (final String type : types) {
                            if (type.equals(ALL_TYPES) || configLocationStr.endsWith(type)) {
                                final Configuration config = factory.getConfiguration(loggerContext, name, configLocation);
                                if (config != null) {
                                    return config;
                                }
                            }
                        }
                    }
                }
            }
            //通过loggerContext获取 配置对象，
            Configuration config = getConfiguration(loggerContext, true, name);
            if (config == null) {
                //此处会读取 log4j2-test.xml文件：
                config = getConfiguration(loggerContext, true, null);
                if (config == null) {
                    config = getConfiguration(loggerContext, false, name);
                    if (config == null) {
                        //走到这，才会去读  log4j2.xml文件：
                        config = getConfiguration(loggerContext, false, null);
                    }
                }
            }
            if (config != null) {
                return config;
            }
            LOGGER.error("No log4j2 configuration file found. " +
                    "Using default configuration: logging only errors to the console. " +
                    "Set system property 'org.apache.logging.log4j.simplelog.StatusLogger.level'" +
                    " to TRACE to show Log4j2 internal initialization logging.");
            //如果log4j2没有添加配置文件，则会使用默认Configuration.
            return new DefaultConfiguration();
        }

        private Configuration getConfiguration(final LoggerContext loggerContext, final String configLocationStr) {
            ConfigurationSource source = null;
            try {
                source = getInputFromUri(NetUtils.toURI(configLocationStr));
            } catch (final Exception ex) {
                // Ignore the error and try as a String.
                LOGGER.catching(Level.DEBUG, ex);
            }
            if (source == null) {
                final ClassLoader loader = LoaderUtil.getThreadContextClassLoader();
                source = getInputFromString(configLocationStr, loader);
            }
            if (source != null) {
                for (final ConfigurationFactory factory : getFactories()) {
                    final String[] types = factory.getSupportedTypes();
                    if (types != null) {
                        for (final String type : types) {
                            if (type.equals(ALL_TYPES) || configLocationStr.endsWith(type)) {
                                final Configuration config = factory.getConfiguration(loggerContext, source);
                                if (config != null) {
                                    return config;
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        //最终获取配置文件的地方：  log4j2-test.xml  或者  log4j2.xml
        private Configuration getConfiguration(final LoggerContext loggerContext, final boolean isTest, final String name) {
            final boolean named = Strings.isNotEmpty(name);
            final ClassLoader loader = LoaderUtil.getThreadContextClassLoader();
            //遍历配置工厂对象，通过遍历出的配置工厂所支持的 文件类型 + 文件前缀 + name来做查找：
            for (final ConfigurationFactory factory : getFactories()) {
                String configName;
//                只有isTest为false 并且name为null ，才能获取到配置文件：
                final String prefix = isTest ? TEST_PREFIX : DEFAULT_PREFIX;
                final String [] types = factory.getSupportedTypes();
                if (types == null) {
                    continue;
                }

                for (final String suffix : types) {
                    if (suffix.equals(ALL_TYPES)) {
                        continue;
                    }
                    configName = named ? prefix + name + suffix : prefix + suffix;

                    //获取配置文件资源对象：
                    final ConfigurationSource source = getInputFromResource(configName, loader);
                    if (source != null) {
                        //此处 XmlCongurationFactory去new了 XmlConfiguration对象；
                        return factory.getConfiguration(loggerContext, source);
                    }
                }
            }
            return null;
        }

        @Override
        public String[] getSupportedTypes() {
            return null;
        }

        @Override
        public Configuration getConfiguration(final LoggerContext loggerContext, final ConfigurationSource source) {
            if (source != null) {
                final String config = source.getLocation();
                for (final ConfigurationFactory factory : getFactories()) {
                    final String[] types = factory.getSupportedTypes();
                    if (types != null) {
                        for (final String type : types) {
                            if (type.equals(ALL_TYPES) || config != null && config.endsWith(type)) {
                                final Configuration c = factory.getConfiguration(loggerContext, source);
                                if (c != null) {
                                    LOGGER.debug("Loaded configuration from {}", source);
                                    return c;
                                }
                                LOGGER.error("Cannot determine the ConfigurationFactory to use for {}", config);
                                return null;
                            }
                        }
                    }
                }
            }
            LOGGER.error("Cannot process configuration, input source is null");
            return null;
        }
    }

    static List<ConfigurationFactory> getFactories() {
        return factories;
    }
}
