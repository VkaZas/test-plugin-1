import com.google.gson.Gson;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.util.HttpURLConnection;

import java.io.DataOutputStream;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;


public class HelloAction extends AnAction {
    public HelloAction() {
        super("Hello");
    }

    public void actionPerformed(AnActionEvent event) {
//        Project project = event.getProject();
//        Messages.showMessageDialog(project, "Hello world!", "Greeting", Messages.getInformationIcon());

        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        System.out.println(project);

        Collection<VirtualFile> virtualFiles = FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project));

        List<String> vfNames = virtualFiles.stream().map(VirtualFile::getName).collect(Collectors.toList());
        List<PsiFile> psiFiles = virtualFiles.stream().map((VirtualFile vf) -> PsiManager.getInstance(project).findFile(vf)).collect(Collectors.toList());

        Map<String, Set<PsiClass>> name2Elements = new HashMap<>();
        psiFiles.forEach((PsiFile pf) -> pf.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClass(PsiClass aClass) {
                super.visitClass(aClass);
                name2Elements.putIfAbsent(pf.getName(), new HashSet<>());
                name2Elements.get(pf.getName()).add(aClass);
            }


        }));

        Map<PsiClass, Set<PsiMethod>> class2Method = new HashMap<>();
        for (Set<PsiClass> psiClasses : name2Elements.values()) {
            for (PsiClass psiClass : psiClasses) {
                class2Method.putIfAbsent(psiClass, new HashSet<>(
                        Arrays.asList(psiClass.getMethods())
                ));
            }
        }

        Map<PsiFile, List<PsiFile>> dependencyGraph = new HashMap<>();
        Map<String, List<String>> name2name = new HashMap<>();
        for (Set<PsiMethod> methods : class2Method.values()) {
            for (PsiMethod method : methods) {
                dependencyGraph.putIfAbsent(method.getContainingFile(), new ArrayList<>());
                name2name.putIfAbsent(method.getContainingFile().getName(), new ArrayList<>());
                ReferencesSearch.search(method).forEach((PsiReference ref) -> {
                    dependencyGraph.get(method.getContainingFile()).add(ref.getElement().getContainingFile());
                    name2name.get(method.getContainingFile().getName()).add(ref.getElement().getContainingFile().getName());
                });
            }
        }

        String jsonNameGraph = new Gson().toJson(name2name);
        sendRequest("http://127.0.0.1:3001/graph", jsonNameGraph);
    }

    private void sendRequest(String route, String data) {
//        HttpURLConnection connection = null;
//
//        try {
//            URL url = new URL(route);
//            connection = (HttpURLConnection)url.openConnection();
//
//            connection.setRequestMethod("POST");
//            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
//            connection.setRequestProperty("Content-Length", Integer.toString(data.getBytes().length));
//            connection.setRequestProperty("Content-Language", "en-US");
//
//            DataOutputStream wr = new DataOutputStream(
//                    connection.getOutputStream()
//            );
//            wr.writeBytes("haha");
//            wr.close();
//        } catch (Exception e) {
//
//        }
        HttpClient client = new HttpClient();
        PostMethod method = new PostMethod(route);
        method.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
        method.setRequestBody(data);
        try {
            int statusCode = client.executeMethod(method);

        } catch (Exception e) {
            System.out.println(e.getStackTrace());
        } finally {
            method.releaseConnection();
        }
    }
}