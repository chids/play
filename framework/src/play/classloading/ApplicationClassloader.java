package play.classloading;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;

public class ApplicationClassloader extends ClassLoader {

    List<File> javaPath;

    public ApplicationClassloader(File... javaPath) {
        super(ApplicationClassloader.class.getClassLoader());
        this.javaPath = Arrays.asList(javaPath);
    }

    @Override
    protected synchronized Class loadClass(String name, boolean resolve) throws ClassNotFoundException {

        // First check if it's an application Class
        Class applicationClass = loadApplicationClass(name);
        if (applicationClass != null) {
            if (resolve) {
                resolveClass(applicationClass);
            }
            return applicationClass;
        }

        // Delegate to the classic classloader
        return super.loadClass(name, resolve);
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~
    protected Class loadApplicationClass(String name) {
        ApplicationClass applicationClass = Play.classes.getApplicationClass(name);
        if (applicationClass != null) {
            if (applicationClass.isCompiled()) {
                return applicationClass.javaClass;
            } else {
                byte[] byteCode = applicationClass.compile();
                applicationClass.javaClass = defineClass(name, byteCode, 0, byteCode.length);
                return applicationClass.javaClass;
            }
        }
        return null;
    }

    protected byte[] getClassDefinition(String name) {
        name = name.replace(".", "/") + ".class";
        InputStream is = getResourceAsStream(name);
        if (is == null) {
            return null;
        }
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = is.read(buffer, 0, buffer.length)) > 0) {
                os.write(buffer, 0, count);
            }
            return os.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void detectChanges() {
        List<ApplicationClass> modifieds = new ArrayList<ApplicationClass>();
        for (ApplicationClass applicationClass : Play.classes.classes.values()) {
            if (applicationClass.timestamp < applicationClass.javaFile.lastModified()) {
                applicationClass.refresh();
                modifieds.add(applicationClass);
            }
        }
        List<ClassDefinition> newDefinitions = new ArrayList<ClassDefinition>();
        for (ApplicationClass applicationClass : modifieds) {
            applicationClass.compile();
            newDefinitions.add(new ClassDefinition(applicationClass.javaClass, applicationClass.javaByteCode));
        }
        try {
            HotswapAgent.reload(newDefinitions.toArray(new ClassDefinition[newDefinitions.size()]));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (UnmodifiableClassException e) {
            throw new RuntimeException(e);
        }
    }
}