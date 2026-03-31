package com.mark.knowledge;

import com.mark.knowledge.auth.config.UserProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(UserProperties.class)
public class KnowledgeApplication {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeApplication.class);
	private static final Path DOT_ENV_PATH = Path.of(".env");

	public static void main(String[] args) {
		loadDotEnv();
		SpringApplication.run(KnowledgeApplication.class, args);
	}

	private static void loadDotEnv() {
		if (!Files.exists(DOT_ENV_PATH)) {
			log.info("未找到 .env 文件，继续使用系统环境变量和 application.yaml 默认值");
			return;
		}

		try {
			List<String> lines = Files.readAllLines(DOT_ENV_PATH);
			for (String rawLine : lines) {
				String line = rawLine.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				int separatorIndex = line.indexOf('=');
				if (separatorIndex <= 0) {
					continue;
				}

				String key = line.substring(0, separatorIndex).trim();
				String value = stripQuotes(line.substring(separatorIndex + 1).trim());
				if (key.isEmpty() || System.getenv(key) != null || System.getProperty(key) != null) {
					continue;
				}

				System.setProperty(key, value);
				log.debug("从 .env 加载配置键: {}", key);
			}
			log.info("已加载 .env 配置，可在 application.yaml 中通过环境变量占位符引用");
		} catch (IOException e) {
			throw new IllegalStateException("读取 .env 文件失败: " + DOT_ENV_PATH.toAbsolutePath(), e);
		}
	}

	private static String stripQuotes(String value) {
		if (value.length() >= 2) {
			boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
			boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
			if (doubleQuoted || singleQuoted) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}

}
