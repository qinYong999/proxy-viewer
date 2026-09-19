package com.proxyviewer.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 集成测试统一配置：完整 Spring 上下文 + H2 内存库（替代 MySQL）+ MockMvc。
 * 组合注解让多个测试类共享同一个上下文缓存，避免重复启动。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:proxyviewer;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.security.enabled=true",
        "app.security.username=tester",
        "app.security.password=secret",
        // 显式清空订阅链接，避免开发机上的 SUBSCRIPTION_URL 环境变量让测试真的去联网抓订阅
        "app.subscription.default-url=",
        "app.test.schedule-enabled=false",
        // 显式清空内核位置与联网配置，避免测试受开发机环境影响：
        // 内核不可用时只影响"真实测试"这一条路径，页面会走「未测」分支。
        "app.test.core-path=",
        "app.test.core-dir=",
        "app.test.core-asset-dir=",
        "app.test.udp-test-enabled=false",
        "app.test.speed-test-enabled=false"
})
@AutoConfigureMockMvc
public @interface IntegrationTest {
}
