package com.ordermanager;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Test-only endpoint that fails unexpectedly, to check what the user sees. */
@Controller
public class BoomController {

    @GetMapping("/test/boom")
    public String boom() {
        throw new IllegalStateException("secret internal detail");
    }
}
