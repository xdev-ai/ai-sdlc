package ai.xdev.aisdlc.portal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Resolves hashed Vite output through the production manifest; no filename is hard-coded in templates. */
@Service
public class ReactAssetService {
  private final ObjectMapper mapper = new ObjectMapper();

  public String entry() {
    try (InputStream input = new ClassPathResource("static/react/manifest.json").getInputStream()) {
      Map<String, Map<String, Object>> manifest = mapper.readValue(input, new TypeReference<>() {});
      Map<String, Object> chunk = manifest.get("src/main.jsx");
      Object file = chunk == null ? null : chunk.get("file");
      return file == null ? "" : "/react/" + file;
    } catch (Exception ignored) {
      return "";
    }
  }
}
