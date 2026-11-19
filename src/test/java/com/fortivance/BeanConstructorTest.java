package com.fortivance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring can only pick a constructor on its own when a bean has exactly one (or a no-argument one).
 * A bean with several constructors must mark one with @Autowired, or the app fails at startup with
 * "No default constructor found". This catches that without starting the app or a database.
 */
class BeanConstructorTest {

    @Test
    void everyBeanHasAnUnambiguousConstructor() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class)); // also matches @Service, @RestController...
        List<String> problems = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.fortivance")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            Constructor<?>[] ctors = type.getDeclaredConstructors();
            if (ctors.length <= 1) {
                continue;
            }
            boolean hasNoArg = Arrays.stream(ctors).anyMatch(c -> c.getParameterCount() == 0);
            long autowired = Arrays.stream(ctors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
            if (autowired != 1 && !hasNoArg) {
                problems.add(type.getName() + " has " + ctors.length + " constructors and " + autowired
                        + " marked @Autowired");
            }
        }
        assertThat(problems).isEmpty();
    }
}
