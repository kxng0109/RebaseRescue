package io.github.kxng0109.rebaserescue.cli.picocli4;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;

/**
 * Exposes the vendored {@link PicocliSpringFactory} as the {@link CommandLine.IFactory} bean.
 */
@AutoConfiguration
public class PicocliBoot4AutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(CommandLine.IFactory.class)
	public CommandLine.IFactory picocliSpringFactory(ApplicationContext applicationContext) {
		return new PicocliSpringFactory(applicationContext);
	}
}
