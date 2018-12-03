import com.google.gson.Gson;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.OpenSourceUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.PostMethod;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;


public class HelloAction extends AnAction {
    private static final String DEPENDENCY_ROUTE = "http://127.0.0.1:3001/col";
    private static final String JAVA_EXT = "java";
    private final HttpServer server;
    private Project project;

    public HelloAction() throws IOException {
        super("CodeBubble IDEA");
        server = HttpServer.create(new InetSocketAddress(3003), 0);
        HelloAction hi = this;
        server.createContext("/test", (HttpExchange exchange) -> {
            try {
                String queryString =  exchange.getRequestURI().getQuery().split("=")[1];
                ApplicationManager.getApplication().invokeLater(
                        () -> ApplicationManager.getApplication().runReadAction(
                                () -> {
                                    Gson gson = new Gson();
                                    String[] queryParams = gson.fromJson(queryString, String[].class);
                                    hi.updateView(queryParams);
                                }
                        )
                );
                exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().close();
                exchange.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        server.start();
    }

    public void actionPerformed(AnActionEvent event) {
        project = ProjectManager.getInstance().getOpenProjects()[0];
        // Mappings from PsiFile to PsiClasses that it owns
        Map<PsiFile, Set<PsiClass>> fileOwnershipMap = new HashMap<>();
        Map<String, Map<String, String>> fileOwnershipNameMap = new HashMap<>();
        // Mappings from PsiClass to PsiMethods that it owns
        Map<PsiClass, Set<PsiMethod>> classOwnershipMap = new HashMap<>();
        Map<String, Map<String, String>> classOwnershipNameMap = new HashMap<>();
        // A dependency graph from declarations to references of PsiClasses represented in PsiFiles with invocation statistics
        Map<PsiFile, Map<PsiFile, Integer>> fileDependencyGraph = new HashMap<>();
        Map<String, Map<String, Integer>> fileDependencyNameGraph = new HashMap<>();

        //

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
                    if (aClass.getQualifiedName() == null || aClass.getQualifiedName().length() == 0) return;

                    super.visitClass(aClass);
                    fileOwnershipMap.putIfAbsent(pf, new HashSet<>());
                    fileOwnershipNameMap.putIfAbsent(pf.getName(), new HashMap<>());
                    fileOwnershipMap.get(pf).add(aClass);
                    fileOwnershipNameMap.get(pf.getName()).putIfAbsent(aClass.getQualifiedName(), aClass.getText());
                }
            });
        });

        // Initialize classOwnershipMap
        for (Set<PsiClass> psiClasses : fileOwnershipMap.values()) {
            for (PsiClass psiClass : psiClasses) {
                classOwnershipMap.putIfAbsent(
                        psiClass,
                        new HashSet<>(Arrays.asList(psiClass.getMethods())
                ));
                classOwnershipNameMap.putIfAbsent(
                        psiClass.getQualifiedName(),
                        classOwnershipMap.get(psiClass).stream().collect(Collectors.toMap(
                                PsiMethod::getName, PsiMethod::getText))
                );
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

        Gson gson = new Gson();
        String jsonNameGraph = gson.toJson(fileDependencyNameGraph);
        String jsonFileOwnershipMap = gson.toJson(fileOwnershipNameMap);
        String jsonClassOwnershipMap = gson.toJson(classOwnershipNameMap);

        sendRequest(
                DEPENDENCY_ROUTE,
                new NameValuePair("dependencyGraph", jsonNameGraph),
                new NameValuePair("fileOwnershipMap", jsonFileOwnershipMap),
                new NameValuePair("classOwnershipMap", jsonClassOwnershipMap)
        );
    }

    private void updateView(String... names) {
        if (names == null) return;

        String className = names.length > 0 ? names[0] : null;
        String methodName = names.length > 1 ? names[1] : null;
        PsiClass targetClass = null;
        PsiMethod targetMethod = null;

        if (className != null) targetClass = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project));

        if (methodName != null) targetMethod = targetClass.findMethodsByName(methodName, false)[0];

        Navigatable target = targetClass == null ? null : targetMethod == null ? targetClass : targetMethod;

        if (target != null) OpenSourceUtil.navigate(target);
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