package com.mark.knowledge;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class KnowledgeApplication {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeApplication.class);

	public static void main(String[] args) {
		// 加载 .env 文件中的环境变量
		Dotenv dotenv = Dotenv.configure()
				.directory(".")
				.filename(".env")
				.ignoreIfMissing()
				.load();

		// 将 .env 中的变量注入到系统属性中，供 Spring Boot 使用
		// .env 文件使用 Spring Boot 标准属性格式 (如 ollama.base-url)
		dotenv.entries().forEach(entry -> {
			String key = entry.getKey();
			String value = entry.getValue();
			System.setProperty(key, value);
			log.debug("注入配置：{}={}", key, value);
		});

		log.info("已从 .env 文件加载配置");

		SpringApplication.run(KnowledgeApplication.class, args);
	}

}
