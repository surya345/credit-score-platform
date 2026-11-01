package dev.credit.platform;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonCodec {
  private final ObjectMapper mapper;

  public JsonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object v) {
    return mapper.writeValueAsString(v);
  }

  public <T> T read(String s, Class<T> type) {
    return mapper.readValue(s, type);
  }
}
