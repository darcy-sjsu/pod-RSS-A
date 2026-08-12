package top.asimov.pigeon.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;


@Controller
public class SpaErrorController implements ErrorController {

  @RequestMapping("/error")
  public Object error(HttpServletRequest request, HttpServletResponse response) {
    if (request.getMethod().equalsIgnoreCase(HttpMethod.GET.name())) {
      response.setStatus(HttpStatus.OK.value()); // optional.
      return "forward:/index.html"; // forward to static SPA html resource.
    } else {
      return ResponseEntity.notFound().build();
    }
  }

}
