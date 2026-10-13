package com.trading;

import com.trading.bucket.BucketProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 테스트용 — <b>실제</b> {@code application.yml} + {@code application-paper.yml}을 읽어 바인딩한다.
 *
 * <p>A동 전환은 전부 설정으로 이뤄진다. 키 이름 오타(예: {@code ma-period})는 예외 없이 기본값(200)으로
 * 조용히 떨어지므로, 코드 기본값이 아니라 배포될 설정 파일 그 자체를 검사해야 한다.
 * paper 파일이 공통 파일보다 우선한다(스프링 프로필 규칙과 같다).
 */
public final class PaperProfileYaml {

    private PaperProfileYaml() {}

    public static MutablePropertySources sources() {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        try {
            loader.load("application-paper.yml", new ClassPathResource("application-paper.yml"))
                    .forEach(sources::addLast);
            loader.load("application.yml", new ClassPathResource("application.yml"))
                    .forEach(sources::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException("설정 파일을 읽지 못했다", e);
        }
        return sources;
    }

    /** {@code @ConfigurationProperties} 클래스 — 운영과 같은 스프링 부트 바인더로 채운다 */
    public static <T> T bind(String prefix, Class<T> type) {
        return new Binder(ConfigurationPropertySources.from(sources())).bindOrCreate(prefix, type);
    }

    /** {@code @Value} 생성자 주입 빈 — 실제 컨테이너로 만들어 기본값 규칙까지 운영과 같게 해석한다 */
    public static BucketProperties bucketProperties() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            sources().forEach(ctx.getEnvironment().getPropertySources()::addLast);
            ctx.register(BucketProperties.class);
            ctx.refresh();
            return ctx.getBean(BucketProperties.class);
        }
    }
}
