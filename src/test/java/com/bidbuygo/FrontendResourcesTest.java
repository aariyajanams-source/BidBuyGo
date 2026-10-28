package com.bidbuygo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bidbuygo.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** The static frontend is public; the API behind it is not. */
@IntegrationTest
class FrontendResourcesTest {

    @Autowired MockMvc mvc;

    @Test
    void rootServesTheAppShellWithoutAToken() throws Exception {
        // "/" is Spring Boot's welcome page: a forward to index.html (MockMvc records forwards instead of following them).
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<script type=\"module\" src=\"app.js\"></script>")))
                .andExpect(content().string(containsString("href=\"style.css\"")));
    }

    @Test
    void scriptAndStylesheetAreServedWithoutAToken() throws Exception {
        mvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(content().string(containsString("export const DEMO_MODE")));
        mvc.perform(get("/style.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    @Test
    void appScriptNeverParsesHtml() throws Exception {
        String js = mvc.perform(get("/app.js")).andReturn().getResponse().getContentAsString();

        // Server data is user input; it must only reach the DOM as text. Comments mentioning the rule are fine.
        String code = js.replaceAll("(?m)^\\s*//.*$", "");
        assertThat(code).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(");
    }

    @Test
    void apiStillRequiresAToken() throws Exception {
        mvc.perform(get("/wallet")).andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/mine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/policy")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherPathsAreNotOpenedUp() throws Exception {
        mvc.perform(get("/secret.js")).andExpect(status().isUnauthorized());
    }
}
