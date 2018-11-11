package customSyntax;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class JavaFile {
    List<JavaClass> classes;
    Map<JavaClass, Set<JavaClass>> innerClassMap;
}
