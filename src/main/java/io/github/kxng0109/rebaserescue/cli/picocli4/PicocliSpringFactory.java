package io.github.kxng0109.rebaserescue.cli.picocli4;

import org.springframework.context.ApplicationContext;
import picocli.CommandLine;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Boot 4-compatible {@link CommandLine.IFactory} vendored in-repo.
 *
 * <p>Upstream {@code picocli-spring-boot-starter:4.7.7} is only tested to Boot 3.1.2
 * and has no Boot 4 artifact (remkop/picocli#2491 open, #2507 unmerged), so the starter dependency was dropped in favor
 * of this minimal equivalent. Looks up command classes in the Spring {@link ApplicationContext} (prototype creation via
 * the autowire-capable factory) and falls back to picocli's default factory.
 */
public class PicocliSpringFactory implements CommandLine.IFactory {

	private static final Logger logger = Logger.getLogger(PicocliSpringFactory.class.getName());

	private final ApplicationContext applicationContext;
	private final CommandLine.IFactory fallbackFactory;

	public PicocliSpringFactory(ApplicationContext applicationContext) {
		this(applicationContext, CommandLine.defaultFactory());
	}

	public PicocliSpringFactory(
			ApplicationContext applicationContext, CommandLine.IFactory fallbackFactory) {
		this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
		this.fallbackFactory = Objects.requireNonNull(fallbackFactory, "fallbackFactory");
	}

	@Override
	public <K> K create(Class<K> clazz) throws Exception {
		try {
			return getBeanOrCreate(clazz);
		} catch (Exception e) {
			logger.info(() -> String.format(
					"Unable to get bean of class %s from ApplicationContext, using fallback factory %s (%s)",
					clazz, fallbackFactory.getClass().getName(), e
			));
			return fallbackFactory.create(clazz);
		}
	}

	private <K> K getBeanOrCreate(Class<K> clazz) {
		try {
			return applicationContext.getBean(clazz);
		} catch (Exception e) {
			return applicationContext.getAutowireCapableBeanFactory().createBean(clazz);
		}
	}
}
