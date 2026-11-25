package net.fabricmc.filament.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/// Finds the Javadoc reference links in a markdown comment, such as `[Object#hashCode()]`, that refer to an existing
/// class or member.
public final class JavadocReferences {
	private static final Pattern FENCED_CODE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*?^ {0,3}\\1[`~]*[ \\t]*$", Pattern.MULTILINE | Pattern.DOTALL);
	private static final Pattern CODE_SPAN = Pattern.compile("(`+).+?\\1", Pattern.DOTALL);
	// A bracketed text that is not part of an inline link, a full or collapsed reference link, or a definition.
	// Empty brackets are allowed inside, for array types like String[]
	private static final Pattern BRACKETS = Pattern.compile("(?<!\\])\\[((?:[^\\[\\]\\n]|\\[])+)](?![(\\[:])");
	private static final String IDENTIFIER = "[A-Za-z_$][\\w$]*";
	private static final Pattern REFERENCE = Pattern.compile(
			"(?<class>" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*)?(?:#(?<member>" + IDENTIFIER + ")(?:\\((?<params>[^()]*)\\))?)?");

	private final ClassPath classPath;

	/// @param classPath the classes that can be referenced, which should include the platform classes
	public JavadocReferences(ClassPath classPath) {
		this.classPath = classPath;
	}

	/// Returns the text of the references in the comment that exist, in order of appearance and without duplicates.
	///
	/// @param comment the markdown comment
	/// @param owner the internal name of the class the comment is on, or of the class containing the member it is on
	public Set<String> findValid(String comment, String owner) {
		Set<String> valid = new LinkedHashSet<>();
		Set<String> checked = new HashSet<>();
		String text = CODE_SPAN.matcher(FENCED_CODE.matcher(comment).replaceAll("")).replaceAll("");
		Matcher matcher = BRACKETS.matcher(text);

		while (matcher.find()) {
			String reference = matcher.group(1);

			if (checked.add(reference) && isValid(reference, owner)) {
				valid.add(reference);
			}
		}

		return valid;
	}

	private boolean isValid(String reference, String owner) {
		Matcher matcher = REFERENCE.matcher(reference);
		if (!matcher.matches()) return false;

		String className = matcher.group("class");
		String member = matcher.group("member");
		String params = matcher.group("params");
		List<String> classes = className != null ? resolveClass(className, owner) : enclosingClasses(owner);

		if (member == null) {
			// Only a class
			return !classes.isEmpty();
		}

		for (String cls : classes) {
			if (hasMember(cls, member, params, new HashSet<>())) return true;
		}

		return false;
	}

	/// Returns the class, then the classes it is nested in.
	private List<String> enclosingClasses(String name) {
		List<String> classes = new ArrayList<>();

		for (String cls = name; ; cls = cls.substring(0, cls.lastIndexOf('$'))) {
			if (classPath.getInfo(cls) != null) classes.add(cls);
			if (cls.lastIndexOf('$') < 0) break;
		}

		return classes;
	}

	/// Returns the existing classes the name could refer to, as it could be written in a Javadoc reference: by simple
	/// name, nested in another class, or fully qualified.
	private List<String> resolveClass(String name, String owner) {
		String[] parts = name.split("\\.");
		List<String> found = new ArrayList<>();

		// The first parts are the package, if any, and the next one is a top level class
		for (int packageLength = 0; packageLength < parts.length; packageLength++) {
			List<String> bases = new ArrayList<>();

			if (packageLength == 0) {
				bases.addAll(simpleNameCandidates(parts[0], owner));
			} else {
				bases.add(String.join("/", List.of(parts).subList(0, packageLength + 1)));
			}

			for (String base : bases) {
				String cls = base + (parts.length > packageLength + 1
						? "$" + String.join("$", List.of(parts).subList(packageLength + 1, parts.length)) : "");

				if (classPath.getInfo(cls) != null && !found.contains(cls)) found.add(cls);
			}
		}

		return found;
	}

	/// The classes a simple name can refer to without an import: nested in the enclosing classes, in `java.lang` or in
	/// the same package. Imports are not known, so any other class with the name is a candidate too.
	private List<String> simpleNameCandidates(String simpleName, String owner) {
		List<String> candidates = new ArrayList<>();

		for (String cls = owner; ; cls = cls.substring(0, cls.lastIndexOf('$'))) {
			if (cls.equals(simpleName) || cls.endsWith("/" + simpleName) || cls.endsWith("$" + simpleName)) {
				candidates.add(cls);
			}

			candidates.add(cls + "$" + simpleName);
			if (cls.lastIndexOf('$') < 0) break;
		}

		candidates.add("java/lang/" + simpleName);
		int slash = owner.lastIndexOf('/');
		candidates.add((slash >= 0 ? owner.substring(0, slash + 1) : "") + simpleName);

		for (String cls : classPath.findBySimpleName(simpleName)) {
			if (cls.indexOf('$') < 0) candidates.add(cls);
		}

		return candidates;
	}

	/// Whether the class, or one of its super types, has a field or method with the name, and parameter types if given.
	private boolean hasMember(String cls, String name, @Nullable String params, Set<String> visited) {
		if (!visited.add(cls)) return false;

		ClassPath.ClassInfo info = classPath.getInfo(cls);
		if (info == null) return false;

		String simple = cls.substring(Math.max(cls.lastIndexOf('/'), cls.lastIndexOf('$')) + 1);
		String methodName = name.equals(simple) ? "<init>" : name;

		for (String member : info.members()) {
			int paren = member.indexOf('(');

			if (paren < 0) {
				// A field, "name:desc"
				if (params == null && member.substring(0, member.indexOf(':')).equals(name)) return true;
			} else if (member.substring(0, paren).equals(methodName)
					&& (params == null || parametersMatch(member.substring(paren, member.indexOf(')') + 1), params))) {
				return true;
			}
		}

		// Constructors are not inherited
		if (methodName.equals("<init>")) return false;

		for (String supertype : info.supertypes()) {
			if (hasMember(supertype, name, params, visited)) return true;
		}

		return false;
	}

	/// Whether the parameter types in a method descriptor match the ones written in a reference, where classes can be
	/// written with their simple or qualified name.
	private static boolean parametersMatch(String descriptor, String params) {
		List<String> expected = parseDescriptor(descriptor);
		String trimmed = params.trim();
		String[] actual = trimmed.isEmpty() ? new String[0] : trimmed.split(",");
		if (expected.size() != actual.length) return false;

		for (int i = 0; i < actual.length; i++) {
			// Only the type, a name after it is allowed
			String type = actual[i].trim().split("\\s+")[0];
			int dimensions = 0;

			if (type.endsWith("...")) {
				type = type.substring(0, type.length() - 3);
				dimensions++;
			}

			while (type.endsWith("[]")) {
				type = type.substring(0, type.length() - 2);
				dimensions++;
			}

			String want = expected.get(i);
			int wantDimensions = 0;

			while (want.endsWith("[]")) {
				want = want.substring(0, want.length() - 2);
				wantDimensions++;
			}

			if (dimensions != wantDimensions || !(want.equals(type) || want.endsWith("." + type))) return false;
		}

		return true;
	}

	/// Returns the parameter types of a method descriptor as written in Java, with `.` for packages and nested classes.
	private static List<String> parseDescriptor(String descriptor) {
		List<String> types = new ArrayList<>();
		int i = 1;

		while (descriptor.charAt(i) != ')') {
			int dimensions = 0;

			while (descriptor.charAt(i) == '[') {
				dimensions++;
				i++;
			}

			String type;

			if (descriptor.charAt(i) == 'L') {
				int end = descriptor.indexOf(';', i);
				type = descriptor.substring(i + 1, end).replace('/', '.').replace('$', '.');
				i = end + 1;
			} else {
				type = switch (descriptor.charAt(i++)) {
				case 'Z' -> "boolean";
				case 'B' -> "byte";
				case 'C' -> "char";
				case 'S' -> "short";
				case 'I' -> "int";
				case 'J' -> "long";
				case 'F' -> "float";
				case 'D' -> "double";
				default -> throw new IllegalArgumentException("Invalid descriptor " + descriptor);
				};
			}

			types.add(type + "[]".repeat(dimensions));
		}

		return types;
	}
}
