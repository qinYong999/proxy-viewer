package com.proxyviewer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证"启动完成后日志输出系统访问地址"这一行为真的发生：
 * 用真实内嵌服务器 + 随机端口启动，断言日志里出现带真实端口的访问地址。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:startupbanner;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.security.enabled=true",
        "app.security.username=tester",
        "app.security.password=secret",
        "app.subscription.default-url=",
        "app.test.schedule-enabled=false",
        "server.address="
})
@ExtendWith(OutputCaptureExtension.class)
class StartupBannerIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void logsAccessUrlWithActualPortOnStartup(CapturedOutput output) {
        assertThat(port).as("内嵌服务器应已分配端口").isGreaterThan(0);

        assertThat(output)
                .as("启动日志应包含系统访问地址")
                .contains("启动完成")
                .contains("系统访问地址: http://localhost:" + port);

        // 未配置订阅链接时应给出提示
        assertThat(output).contains("订阅链接: 未配置");
    }
}
