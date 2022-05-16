package me.mrletsplay.resourceindex;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Goal which touches a timestamp file.
 */
@Mojo(name = "generate-index", defaultPhase = LifecyclePhase.COMPILE)
public class ResourceIndex extends AbstractMojo {

	/**
	 * Where to store the resources index in the JAR file
	 */
	@Parameter(defaultValue = "resources.list", property = "targetPath", required = true)
	private String targetPath;

	/**
	 * Whether to append to an existing resource index file instead of replacing it
	 */
	@Parameter(defaultValue = "false", property = "appendIfExists", required = true)
	private boolean appendIfExists;

	/**
	 * A list of resources to exclude. This must contain the final target paths to the resources
	 */
	@Parameter(property = "excludes", required = true)
	private String[] excludes = new String[0];

	/**
	 * The project to get the JavaFX dependencies from
	 */
	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject mavenProject;

	@Override
	public void execute() throws MojoExecutionException {
		List<String> excludeList = Arrays.asList(excludes);

		List<String> resources = new ArrayList<>();
		for(Resource r : mavenProject.getResources()) {
			getLog().info("Reading resources from " + r.getDirectory());
			Path path = Path.of(r.getDirectory());
			Path target = Path.of(r.getTargetPath() == null ? "" : r.getTargetPath());
			List<String> absInclude = r.getIncludes().stream().map(i -> path.toAbsolutePath().toString() + "/" + i).collect(Collectors.toList());
			List<String> absExclude = r.getExcludes().stream().map(e -> path.toAbsolutePath().toString() + "/" + e).collect(Collectors.toList());
			getLog().debug("Include: " + absInclude + ", Exclude: " + absExclude);
			Predicate<String> include = f -> absInclude.isEmpty()
				|| absInclude.stream().anyMatch(i -> FilenameUtils.wildcardMatch(f, i));
			Predicate<String> exclude = f -> absExclude.stream().anyMatch(e -> FilenameUtils.wildcardMatch(f, e));
			try {
				Files.walk(path).forEach(f -> {
					File file = f.toFile();
					Path relative = path.relativize(f); // Resource path relative to the original resource directory
					Path absolute = f.toAbsolutePath(); // Absolute path to the resource on disk
					if(file.isFile()) {
						if(!include.test(absolute.toString()) || exclude.test(absolute.toString())) return;
						String targetPath = target.resolve(relative).toString();
						if(excludeList.contains(targetPath)) return;
						getLog().info("- " + path.relativize(f));
						resources.add(targetPath); // Add the path relative to the JAR root (targetDir/relative)
					}
				});
			} catch (IOException e) {
				throw new MojoExecutionException("Failed to walk resources", e);
			}
		}

		Path idxPath = Path.of(mavenProject.getBuild().getOutputDirectory(), targetPath);
		getLog().info("Writing resource index file to " + idxPath);
		try {
			Set<String> base = new HashSet<>(); // We don't want duplicate resource entries
			if(Files.exists(idxPath) && appendIfExists) {
				getLog().info("Appending to existing resource index file");
				base = new HashSet<>(Files.readAllLines(idxPath, StandardCharsets.UTF_8));
			}
			base.addAll(resources);
			Files.writeString(idxPath, base.stream().collect(Collectors.joining("\n")), StandardCharsets.UTF_8);
		}catch(IOException e) {
			throw new MojoExecutionException("Failed to walk resources", e);
		}
	}
}
