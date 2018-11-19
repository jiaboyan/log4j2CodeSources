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
package org.apache.logging.log4j.util;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.spi.Provider;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * <em>Consider this class private.</em> Utility class for Log4j {@link Provider}s. When integrating with an application
 * container framework, any Log4j Providers not accessible through standard classpath scanning should
 * {@link #loadProvider(java.net.URL, ClassLoader)} a classpath accordingly.
 */
public final class ProviderUtil {

    /**
     * Resource name for a Log4j 2 provider properties file.
     */
    protected static final String PROVIDER_RESOURCE = "META-INF/log4j-provider.properties";

    /**
     * Loaded providers.
     */
    protected static final Collection<Provider> PROVIDERS = new HashSet<>();

    /**
     * Guards the ProviderUtil singleton instance from lazy initialization. This is primarily used for OSGi support.
     *
     * @since 2.1
     */
    protected static final Lock STARTUP_LOCK = new ReentrantLock();

    private static final String API_VERSION = "Log4jAPIVersion";
    private static final String[] COMPATIBLE_API_VERSIONS = {"2.6.0"};
    private static final Logger LOGGER = StatusLogger.getLogger();

    // STARTUP_LOCK guards INSTANCE for lazy initialization; this allows the OSGi Activator to pause the startup and
    // wait for a Provider to be installed. See LOG4J2-373
    private static volatile ProviderUtil instance;

    private ProviderUtil() {
//        通过classCloader去加载 META-INF/log4j-provider.properties 文件，实际保存在log4j-core.jar下；
        for (final LoaderUtil.UrlResource resource : LoaderUtil.findUrlResources(PROVIDER_RESOURCE)) {
            //实际加载 META-INF/log4j-provider.properties 的内容，
            loadProvider(resource.getUrl(), resource.getClassLoader());
        }
    }

    /**
     * Loads an individual Provider implementation. This method is really only useful for the OSGi bundle activator and
     * this class itself.
     *
     * @param url the URL to the provider properties file
     * @param cl the ClassLoader to load the provider classes with
     */
//    加载 log4j-core.jar下的 META-INF/log4j-provider.properties 中的内容：
    protected static void loadProvider(final URL url, final ClassLoader cl) {
        try {
            final Properties props = PropertiesUtil.loadClose(url.openStream(), url);
            //判断配置文件中的 Log4jAPIVersion 属性的值，是否满足代码版本的要求：
            if (validVersion(props.getProperty(API_VERSION))) {
                //new provider对象，将配置文件中的属性赋值给Provider对象
                final Provider provider = new Provider(props, url, cl);
                //添加到 providers集合中：
                PROVIDERS.add(provider);
                LOGGER.debug("Loaded Provider {}", provider);
            }
        } catch (final IOException e) {
            LOGGER.error("Unable to open {}", url, e);
        }
    }

    /**
     * @deprecated Use {@link #loadProvider(java.net.URL, ClassLoader)} instead. Will be removed in 3.0.
     */
    @Deprecated
    protected static void loadProviders(final Enumeration<URL> urls, final ClassLoader cl) {
        if (urls != null) {
            while (urls.hasMoreElements()) {
                loadProvider(urls.nextElement(), cl);
            }
        }
    }

    public static Iterable<Provider> getProviders() {
        lazyInit();
        return PROVIDERS;
    }

    public static boolean hasProviders() {
        //懒加载：加载META-INF/log4j-provider.properties配置文件，创建Provider对象，添加到PROVIDERS集合中；
        lazyInit();
        //判断 Provider集合是否为空：
        return !PROVIDERS.isEmpty();
    }

    /**
     * Lazily initializes the ProviderUtil singleton.
     *
     * @since 2.1
     */
    protected static void lazyInit() {
        //ProviderUtil是否为null
        if (instance == null) {
            try {
                //加锁：
                STARTUP_LOCK.lockInterruptibly();
                try {
                    //此时再次判断ProviderUtil是否为null ，为null的话new对象：
                    if (instance == null) {
//                        内部加载META-INF/log4j-provider.properties文件，创建Provider，添加到PROVIDERS集合中
                        instance = new ProviderUtil();
                    }
                } finally {
//                    释放锁
                    STARTUP_LOCK.unlock();
                }
            } catch (final InterruptedException e) {
                LOGGER.fatal("Interrupted before Log4j Providers could be loaded.", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    public static ClassLoader findClassLoader() {
        return LoaderUtil.getThreadContextClassLoader();
    }

    //检查配置文件中的版本，与代码中的版本是否兼容：
    private static boolean validVersion(final String version) {
        for (final String v : COMPATIBLE_API_VERSIONS) {
            if (version.startsWith(v)) {
                return true;
            }
        }
        return false;
    }
}
