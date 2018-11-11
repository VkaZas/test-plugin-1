import com.google.gson.Gson;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.PostMethod;

import java.util.*;
import java.util.stream.Collectors;


public class HelloAction extends AnAction {
    private static final String DEPENDENCY_ROUTE = "http://127.0.0.1:3001/graph";
    private static final String JAVA_EXT = "java";

    public HelloAction() {
        super("CodeBubble IDEA");
    }

    public void actionPerformed(AnActionEvent event) {
        // Mappings from PsiFile to PsiClasses that it owns
        Map<PsiFile, Set<PsiClass>> fileOwnershipMap = new HashMap<>();
        Map<String, Set<String>> fileOwnershipNameMap = new HashMap<>();
        // Mappings from PsiClass to PsiMethods that it owns
        Map<PsiClass, Set<PsiMethod>> classOwnershipMap = new HashMap<>();
        Map<String, Set<String>> classOwnershipNameMap = new HashMap<>();
        // A dependency graph from declarations to references of PsiClasses represented in PsiFiles with invocation statistics
        Map<PsiFile, Map<PsiFile, Integer>> fileDependencyGraph = new HashMap<>();
        Map<String, Map<String, Integer>> fileDependencyNameGraph = new HashMap<>();

        // Get PisProject
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        // Filter out VirtualFiles without .java extension postfix
        Collection<VirtualFile> virtualFiles = FilenameIndex.getAllFilesByExt(project, JAVA_EXT, GlobalSearchScope.projectScope(project));

        /*
        Output:
        1. Files -> Classes
        2. Class -> Methods
        2. File dependency graph
         */

        // Initialize fileOwnershipMap & fileOwnershipNameMap
        virtualFiles.forEach((VirtualFile vf) -> {
            PsiFile pf = PsiManager.getInstance(project).findFile(vf);
            pf.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitClass(PsiClass aClass) {
                    if (aClass.getName() == null || aClass.getName().length() == 0) return;

                    super.visitClass(aClass);
                    fileOwnershipMap.putIfAbsent(pf, new HashSet<>());
                    fileOwnershipNameMap.putIfAbsent(pf.getName(), new HashSet<>());
                    fileOwnershipMap.get(pf).add(aClass);
                    fileOwnershipNameMap.get(pf.getName()).add(aClass.getName());
                }
            });
        });

        // Initialize classOwnershipMap
        for (Set<PsiClass> psiClasses : fileOwnershipMap.values()) {
            for (PsiClass psiClass : psiClasses) {
                classOwnershipMap.putIfAbsent(psiClass, new HashSet<>(
                        Arrays.asList(psiClass.getMethods())
                ));
                classOwnershipNameMap.putIfAbsent(psiClass.getName(), classOwnershipMap.get(psiClass).stream().map(PsiMethod::getName).collect(Collectors.toSet()));
            }
        }

        fileOwnershipMap.forEach((psiFile, psiClasses) -> {
            fileDependencyGraph.putIfAbsent(psiFile, new HashMap<>());
            Map<PsiFile, Integer> referenceCountMap = fileDependencyGraph.get(psiFile);

            for (PsiClass psiClass : psiClasses) {
                ReferencesSearch.search(psiClass).forEach((PsiReference pr) -> {
                    PsiFile referencerFile = pr.getElement().getContainingFile();
                    if (referencerFile.getName().endsWith("java"))
                        referenceCountMap.put(
                                referencerFile,
                                referenceCountMap.getOrDefault(referencerFile, 0) + 1
                        );
                });
            }

            fileDependencyNameGraph.putIfAbsent(
                    psiFile.getName(),
                    referenceCountMap.entrySet().stream().collect(
                            Collectors.toMap(e -> e.getKey().getName(), Map.Entry::getValue)
                    )
            );
        });

        String jsonNameGraph = new Gson().toJson(fileDependencyNameGraph);
        String jsonFileOwnershipMap = new Gson().toJson(fileOwnershipNameMap);
        String jsonClassOwnershipMap = new Gson().toJson(classOwnershipNameMap);

        sendRequest(
                DEPENDENCY_ROUTE,
                new NameValuePair("dependencyGraph", jsonNameGraph),
                new NameValuePair("fileOwnershipMap", jsonFileOwnershipMap),
                new NameValuePair("classOwnershipMap", jsonClassOwnershipMap)
        );
    }

    private void sendRequest(String route, NameValuePair... pairs) {
        HttpClient client = new HttpClient();
        PostMethod method = new PostMethod(route);
        method.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
        method.setRequestBody(pairs);

        try {
            int statusCode = client.executeMethod(method);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        } finally {
            method.releaseConnection();
        }
    }
}