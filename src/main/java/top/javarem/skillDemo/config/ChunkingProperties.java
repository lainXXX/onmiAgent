package top.javarem.skillDemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "rag.chunking")
public class ChunkingProperties {

    private List<String> separators = new ArrayList<>();

    private int parentChunkSize = 800;
    private int childChunkSize = 200;

    private int parentOverlap = 50;
    private int childOverlap = 20;

    private boolean enableStructureAware = true;
    private boolean enableBreadcrumbs = true;

    private int maxRecursionDepth = 20;
    private int maxChunkSize = 10000;

    public ChunkingProperties() {
        this.separators.add("\n\n");
        this.separators.add("\n");
        this.separators.add("。");
        this.separators.add("；");
        this.separators.add("，");
        this.separators.add(" ");
        this.separators.add("");
    }
}
