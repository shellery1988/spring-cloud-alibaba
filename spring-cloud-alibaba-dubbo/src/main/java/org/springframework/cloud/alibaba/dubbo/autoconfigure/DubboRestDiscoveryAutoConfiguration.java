/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.alibaba.dubbo.autoconfigure;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import feign.Client;
import feign.Request;
import feign.Response;
import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cloud.alibaba.dubbo.rest.feign.RestMetadataResolver;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.context.named.NamedContextFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Auto-Configuration class for Dubbo REST Discovery
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 */
@Configuration
@AutoConfigureAfter(value = {
        DubboRestAutoConfiguration.class,
        DubboRestMetadataRegistrationAutoConfiguration.class})
public class DubboRestDiscoveryAutoConfiguration {

    @Autowired
    private DiscoveryClient discoveryClient;

    @Autowired
    private RestMetadataResolver restMetadataResolver;

    @Autowired(required = false)
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Feign Request -> Dubbo ReferenceBean
     */
    private Map<String, ReferenceBean> referenceBeanCache = new HashMap<>();

    @Autowired
    private ApplicationConfig applicationConfig;

    @Value("${spring.cloud.nacos.discovery.server-addr}")
    private String nacosServerAddress;


    private volatile boolean initialized = false;

    @Autowired
    private ListableBeanFactory beanFactory;

    @Scheduled(initialDelay = 10 * 1000, fixedRate = 5000)
    public void init() {

        if (initialized) {
            return;
        }

        Map<String, NamedContextFactory.Specification> specifications =
                beanFactory.getBeansOfType(NamedContextFactory.Specification.class);
        ServiceAnnotationBeanPostProcessor
        // 1. Get all service names from Spring beans that was annotated by @FeignClient
        List<String> serviceNames = new LinkedList<>();

        specifications.forEach((beanName, specification) -> {
            String serviceName = beanName.substring(0, beanName.indexOf("."));
            serviceNames.add(serviceName);

            // 2. Get all service instances by echo specified service name
            List<ServiceInstance> serviceInstances = discoveryClient.getInstances(serviceName);
            if (!serviceInstances.isEmpty()) {
                ServiceInstance serviceInstance = serviceInstances.get(0);
                // 3. Get Rest metadata from service instance
                Map<String, String> metadata = serviceInstance.getMetadata();
                // 4. Resolve REST metadata from the @FeignClient instance
                String restMetadataJson = metadata.get("restMetadata");
                /**
                 * {
                 *   "providers:org.springframework.cloud.alibaba.dubbo.service.EchoService:1.0.0": [
                 *     "{\"method\":\"POST\",\"url\":\"/plus?a={a}&b={b}\",\"headers\":{}}",
                 *     "{\"method\":\"GET\",\"url\":\"/echo?message={message}\",\"headers\":{}}"
                 *   ]
                 * }
                 */
                try {
                    Map<String, List<String>> restMetadata = objectMapper.readValue(restMetadataJson, Map.class);

                    restMetadata.forEach((dubboServiceName, restJsons) -> {
                        restJsons.stream().map(restMetadataResolver::resolveRequest).forEach(request -> {
                            referenceBeanCache.put(request.toString(), buildReferenceBean(dubboServiceName));
                        });
                    });

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                //
            }
        });

        initialized = true;

    }

    private ReferenceBean buildReferenceBean(String dubboServiceName) {
        ReferenceBean referenceBean = new ReferenceBean();
        applicationConfig.setName("service-consumer");
        referenceBean.setApplication(applicationConfig);
        RegistryConfig registryConfig = new RegistryConfig();
        // requires dubbo-registry-nacos
        registryConfig.setAddress("nacos://" + nacosServerAddress);
        referenceBean.setRegistry(registryConfig);
        String[] parts = StringUtils.delimitedListToStringArray(dubboServiceName, ":");
        referenceBean.setInterface(parts[1]);
        referenceBean.setVersion(parts[2]);
        referenceBean.setGroup(parts.length > 3 ? parts[3] : null);
        referenceBean.get();
        return referenceBean;
    }

    @Bean
    public BeanPostProcessor wrapClientBeanPostProcessor() {
        return new BeanPostProcessor() {
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof Client) {
                    Client client = (Client) bean;
                    // wrapper
                    return new DubboFeignClientProxy(client);
                }
                return bean;
            }
        };
    }

    class DubboFeignClientProxy implements Client {

        private final Client delegate;

        DubboFeignClientProxy(Client delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response execute(Request request, Request.Options options) throws IOException {

            ReferenceBean referenceBean = referenceBeanCache.get(request.toString());

            if (referenceBean != null) {
                Object dubboClient = referenceBean.get();
                Method method = null;
                Object[] params = null;

                try {
                    Object result = method.invoke(dubboClient, params);
                    // wrapper as a Response
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
            }

            return delegate.execute(request, options);
        }
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {

    }


}
