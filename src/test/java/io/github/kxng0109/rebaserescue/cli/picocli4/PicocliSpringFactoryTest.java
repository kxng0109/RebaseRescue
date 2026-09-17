package io.github.kxng0109.rebaserescue.cli.picocli4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PicocliSpringFactoryTest {

	@Mock
	private ApplicationContext applicationContext;

	@Mock
	private AutowireCapableBeanFactory beanFactory;

	@Mock
	private CommandLine.IFactory fallbackFactory;

	@Test
	void create_shouldReturnSpringBean_whenPresent() throws Exception {
		Object bean = new Object();
		when(applicationContext.getBean(Object.class)).thenReturn(bean);
		PicocliSpringFactory factory = new PicocliSpringFactory(applicationContext, fallbackFactory);

		assertThat(factory.create(Object.class)).isSameAs(bean);
		verify(fallbackFactory, never()).create(Object.class);
	}

	@Test
	void create_shouldAutowire_whenNoBeanDefined() throws Exception {
		Object created = new Object();
		when(applicationContext.getBean(Object.class))
				.thenThrow(new NoSuchBeanDefinitionException(Object.class));
		when(applicationContext.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
		when(beanFactory.createBean(Object.class)).thenReturn(created);
		PicocliSpringFactory factory = new PicocliSpringFactory(applicationContext, fallbackFactory);

		assertThat(factory.create(Object.class)).isSameAs(created);
		verify(fallbackFactory, never()).create(Object.class);
	}

	@Test
	void create_shouldUseFallback_whenSpringFails() throws Exception {
		Object fallback = new Object();
		when(applicationContext.getBean(Object.class))
				.thenThrow(new NoSuchBeanDefinitionException(Object.class));
		when(applicationContext.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
		when(beanFactory.createBean(Object.class)).thenThrow(new RuntimeException("boom"));
		when(fallbackFactory.create(Object.class)).thenReturn(fallback);
		PicocliSpringFactory factory = new PicocliSpringFactory(applicationContext, fallbackFactory);

		assertThat(factory.create(Object.class)).isSameAs(fallback);
	}

	@Test
	void constructor_shouldRejectNullApplicationContext() {
		assertThatThrownBy(() -> new PicocliSpringFactory(null, fallbackFactory))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void constructor_shouldRejectNullFallback() {
		assertThatThrownBy(() -> new PicocliSpringFactory(applicationContext, null))
				.isInstanceOf(NullPointerException.class);
	}
}
