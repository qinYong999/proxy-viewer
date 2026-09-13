package com.proxyviewer;

import com.proxyviewer.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓库卫生检查：订阅链接必须是**纯配置项**。
 *
 * <p>原因：订阅地址会暴露机场入口，写进仓库就等于写进版本历史（即使之后删除也仍留在历史提交里）。
 * 因此 {@code app.subscription.default-url} 只能为空占位形式，且源码/模板里不得再内联任何订阅地址。</p>
 *
 * <p>本测试使用通用规则判断，本身不含任何真实订阅链接。</p>
 */
class ConfigurationHygieneTest {

    /** 允许且唯一的写法：环境变量占位、默认值为空 */
    private static final String EXPECTED_ASSIGNMENT = "app.subscription.default-url=${SUBSCRIPTION_URL:}";

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void appPropertiesDefaultIsBlank() {
        assertThat(new AppProperties().getSubscription().getDefaultUrl()).isBlank();
    }

    @Test
    void applicationPropertiesShipsNoSubscriptionLink() throws IOException {
        List<String> assignments = Files.readAllLines(RESOURCES.resolve("application.properties"),
                        StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("app.subscription.default-url"))
                .toList();

        assertThat(assignments)
                .as("application.properties 中的 default-url 必须为空占位，不得内置订阅链接")
                .containsExactly(EXPECTED_ASSIGNMENT);
    }

    /** 仓库配置保持中立：本地 dev profile 只能由运行参数/环境变量激活，不能写进提交的配置 */
    @Test
    void committedPropertiesDoNotActivateAnyProfile() throws IOException {
        List<String> activations = Files.readAllLines(RESOURCES.resolve("application.properties"),
                        StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.startsWith("spring.profiles.active")
                        || line.startsWith("spring.config.activate.on-profile"))
                .toList();

        assertThat(activations)
                .as("不应在提交的配置里激活 profile（应改用运行参数，见 .run/ProxyViewer-dev.run.xml）")
                .isEmpty();
    }

    /** IDEA 共享运行配置必须存在，否则本地私有配置（application-dev.properties）不会被加载 */
    @Test
    void ideaSharedRunConfigurationActivatesDevProfile() throws IOException {
        Path runConfig = Path.of(".run", "ProxyViewer-dev.run.xml");

        assertThat(runConfig).as("缺少 IDEA 共享运行配置").exists();
        String content = Files.readString(runConfig, StandardCharsets.UTF_8);
        assertThat(content)
                .contains("-Dspring.profiles.active=dev")
                .contains("com.proxyviewer.Application");
    }

    @Test
    void noSourceFileHardcodesADefaultSubscriptionUrl() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main"))) {
            List<String> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(ConfigurationHygieneTest::isScannable)
                    .flatMap(path -> linesOf(path).stream().map(line -> path + ": " + line.trim()))
                    .filter(line -> line.matches(".*default-url\\s*=\\s*\\S+.*"))
                    .filter(line -> !line.contains("${SUBSCRIPTION_URL:}"))
                    .toList();

            assertThat(offenders)
                    .as("这些文件里内联了订阅地址，应改为配置项")
                    .isEmpty();
        }
    }

    private static boolean isScannable(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        // 私有的 profile 配置文件（含本机订阅链接/口令）被 .gitignore 忽略，不属于仓库内容
        if (name.startsWith("application-") && !name.equals("application.properties")) {
            return false;
        }
        return name.endsWith(".java") || name.endsWith(".properties")
                || name.endsWith(".html") || name.endsWith(".xml");
    }

    private static List<String> linesOf(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }
}
