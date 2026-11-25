package net.fabricmc.filament.util;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/// Lazily reads the members of classes by their internal name from a set of jars.
public final class ClassPath implements Closeable {
	private final List<ZipFile> jars = new ArrayList<>();
	private final boolean includePlatform;
	private final Map<String, Optional<ClassInfo>> cache = new HashMap<>();
	private @Nullable Map<String, List<String>> simpleNames;

	public ClassPath(Iterable<File> files) throws IOException {
		this(files, false);
	}

	/// @param includePlatform whether to include JDK classes
	public ClassPath(Iterable<File> files, boolean includePlatform) throws IOException {
		this.includePlatform = includePlatform;

		for (File file : files) {
			jars.add(new ZipFile(file));
		}
	}

	/// Returns the members of the class, "name:desc" for fields and "namedesc" for methods, or null if it does not exist.
	public @Nullable Set<String> getMembers(String name) {
		ClassInfo info = getInfo(name);
		return info != null ? info.members() : null;
	}

	/// Returns the members and the direct supertypes of the class, or null if it does not exist.
	public @Nullable ClassInfo getInfo(String name) {
		return cache.computeIfAbsent(name, k -> {
			try {
				return Optional.ofNullable(read(name + ".class"));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}).orElse(null);
	}

	/// Returns the internal names of the classes in the jars with this simple name, nested classes included.
	public List<String> findBySimpleName(String simpleName) {
		if (simpleNames == null) {
			Map<String, List<String>> index = new HashMap<>();

			for (ZipFile jar : jars) {
				jar.stream().forEach(entry -> {
					String file = entry.getName();
					if (!file.endsWith(".class") || file.startsWith("META-INF/")) return;

					String name = file.substring(0, file.length() - ".class".length());
					String simple = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('$')) + 1);
					index.computeIfAbsent(simple, k -> new ArrayList<>()).add(name);
				});
			}

			simpleNames = index;
		}

		return simpleNames.getOrDefault(simpleName, List.of());
	}

	private @Nullable ClassInfo read(String file) throws IOException {
		for (ZipFile jar : jars) {
			ZipEntry entry = jar.getEntry(file);
			if (entry == null) continue;

			try (InputStream is = jar.getInputStream(entry)) {
				return parse(is);
			}
		}

		if (includePlatform) {
			// A null stream is skipped by the try-with-resources
			try (InputStream is = ClassLoader.getPlatformClassLoader().getResourceAsStream(file)) {
				if (is != null) return parse(is);
			}
		}

		return null;
	}

	private static ClassInfo parse(InputStream is) throws IOException {
		Set<String> members = new HashSet<>();
		List<String> supertypes = new ArrayList<>();
		new ClassReader(is).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int version, int access, String name, @Nullable String signature, @Nullable String superName, String @Nullable [] interfaces) {
				if (superName != null) supertypes.add(superName);
				if (interfaces != null) supertypes.addAll(List.of(interfaces));
			}

			@Override
			public @Nullable FieldVisitor visitField(int access, String name, String descriptor, @Nullable String signature, @Nullable Object value) {
				members.add(name + ":" + descriptor);
				return null;
			}

			@Override
			public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor, @Nullable String signature, String @Nullable [] exceptions) {
				members.add(name + descriptor);
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return new ClassInfo(members, supertypes);
	}

	@Override
	public void close() throws IOException {
		for (ZipFile jar : jars) {
			jar.close();
		}
	}

	/// The members of a class, as in {@link #getMembers}, and the internal names of its super class and interfaces.
	public record ClassInfo(Set<String> members, List<String> supertypes) {
	}
}
