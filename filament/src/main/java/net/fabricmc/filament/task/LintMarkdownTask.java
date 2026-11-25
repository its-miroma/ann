package net.fabricmc.filament.task;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

import net.fabricmc.filament.util.ClassPath;
import net.fabricmc.filament.util.JavadocReferences;
import net.fabricmc.filament.util.Mappings;
import net.fabricmc.mappingio.tree.MappingTree;

public abstract class LintMarkdownTask extends DefaultTask {
	@InputDirectory
	public abstract DirectoryProperty getMappings();

	@InputFiles
	public abstract ConfigurableFileCollection getMinecraftJars();

	@OutputDirectory
	public abstract DirectoryProperty getOutput();

	@Inject
	protected abstract ExecOperations getExecOperations();

	@TaskAction
	public void run() throws IOException {
		Path mappingsDir = getMappings().get().getAsFile().toPath();
		Path outputDir = getOutput().get().getAsFile().toPath();

		if (Files.exists(outputDir)) {
			// https://github.com/FabricMC/fabric-loom/blob/ff8ed1a0e834d3645877c5e0eccd2b13c697dab4/src/main/java/net/fabricmc/loom/util/DeletingFileVisitor.java#L34
			Files.walkFileTree(outputDir, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes _attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException _exc) throws IOException {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}

		Files.createDirectories(outputDir);

		var tree = Mappings.read(mappingsDir);

		try (SegmentWriter writer = new SegmentWriter(outputDir, new ClassPath(getMinecraftJars(), true))) {
			for (var claß : tree.getClasses()) {
				writer.write(claß);

				for (var field : claß.getFields()) {
					writer.write(field);
				}

				for (var method : claß.getMethods()) {
					writer.write(method);
				}
			}
		}

		// TODO: test on Windows
		this.getExecOperations().exec(spec -> {
			spec.commandLine("npx", "markdownlint-cli2", outputDir.resolve("**/*.md"));
		}).assertNormalExitValue();
	}

	private static final class SegmentWriter implements AutoCloseable {
		private final Path outputDir;
		private final ClassPath classPath;
		private final JavadocReferences references;

		SegmentWriter(Path outputDir, ClassPath classPath) {
			this.outputDir = outputDir;
			this.classPath = classPath;
			this.references = new JavadocReferences(classPath);
		}

		void write(MappingTree.ElementMapping element) throws IOException {
			var comment = element.getComment();
			if (comment == null) {
				return;
			}

			var key = Mappings.key(element);
			var content = new StringBuilder("""
				# `%s`

				%s
				""".formatted(key, comment));

			Set<String> valid = references.findValid(comment, owner(element));

			if (!valid.isEmpty()) {
				content.append('\n');
				valid.forEach(reference -> content.append('[').append(reference).append("]: #\n"));
			}

			Files.writeString(outputDir.resolve(fileName(key)), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		}

		private static String owner(MappingTree.ElementMapping element) {
			return element instanceof MappingTree.MemberMapping member ? member.getOwner().getSrcName() : element.getSrcName();
		}

		private static String fileName(String key) {
			return key.replaceAll("[^A-Za-z0-9_$.()\\[\\]-]", "_") + ".md";
		}

		@Override
		public void close() throws IOException {
			classPath.close();
		}
	}
}
