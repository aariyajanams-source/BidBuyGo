package com.bidbuygo.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Use on every Spring integration test instead of a bare @SpringBootTest. Identical configuration means one cached
 * Spring context for the whole suite; a second context would re-run create-drop against the shared in-memory H2
 * database and wipe the first one's data.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
public @interface IntegrationTest {
}
