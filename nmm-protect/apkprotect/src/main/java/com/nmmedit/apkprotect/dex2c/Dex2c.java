package com.nmmedit.apkprotect.dex2c;

import com.google.common.collect.Maps;
import com.nmmedit.apkprotect.dex2c.converter.ClassAnalyzer;
import com.nmmedit.apkprotect.dex2c.converter.JniCodeGenerator;
import com.nmmedit.apkprotect.dex2c.converter.instructionrewriter.InstructionRewriter;
import com.nmmedit.apkprotect.dex2c.converter.structs.ClassMethodToNative;
import com.nmmedit.apkprotect.dex2c.converter.structs.ClassToSymDex;
import com.nmmedit.apkprotect.dex2c.converter.structs.LoadLibClassDef;
import com.nmmedit.apkprotect.dex2c.converter.structs.RegisterNativesCallerClassDef;
import com.nmmedit.apkprotect.dex2c.filters.ClassAndMethodFilter;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Dex2c {

    public static final String LANDROID_APP_APPLICATION = "Landroid/app/Application;";

    private Dex2c() {
    }

    /**
     * 处理多个dex文件
     *
     * @param dexFiles dex文件列表
     * @param outDir   生成c文件等输出目录
     * @return 输出结果配置
     * @throws IOException
     */
    public static GlobalDexConfig handleDexes(List<File> dexFiles,
                                              ClassAndMethodFilter filter,
                                              InstructionRewriter instructionRewriter,
                                              File outDir) throws IOException {
        if (!outDir.exists()) outDir.mkdirs();
        final GlobalDexConfig globalConfig = new GlobalDexConfig(outDir);
        for (File file : dexFiles) {
            final DexConfig config = handleDex(file, filter, instructionRewriter, outDir);
            globalConfig.addDexConfig(config);
        }
        globalConfig.generateJniInitCode();
        return globalConfig;
    }

    /**
     * 处理单个dex文件
     *
     * @param dexFile dex文件
     * @param outDir  输出目录
     * @return 输出配置
     * @throws IOException
     */
    public static DexConfig handleDex(File dexFile,
                                      ClassAndMethodFilter filter,
                                      InstructionRewriter instructionRewriter,
                                      File outDir) throws IOException {
        return handleDex(new BufferedInputStream(new FileInputStream(dexFile)),
                dexFile.getName(),
                filter,
                instructionRewriter,
                outDir);
    }

    /**
     * 处理单个dex流
     *
     * @param dex         dex流
     * @param dexFileName dex名,输出文件需要
     * @param outDir      输出目录
     * @return 输出配置
     * @throws IOException
     */
    public static DexConfig handleDex(InputStream dex,
                                      String dexFileName,
                                      ClassAndMethodFilter filter,
                                      InstructionRewriter instructionRewriter,
                                      File outDir) throws IOException {
        DexBackedDexFile originDexFile = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(),
                dex);

        //把方法变为本地方法,用它替换掉原本的dex
        DexPool shellDexPool = new DexPool(Opcodes.getDefault());

        DexPool nativeImplDexPool = new DexPool(Opcodes.getDefault());


        for (final ClassDef classDef : originDexFile.getClasses()) {
            if (filter.acceptClass(classDef)) {
                //把需要转换的方法设为native
                shellDexPool.internClass(new ClassMethodToNative(classDef, filter));
                //收集所有需要转换的方法生成新dex
                nativeImplDexPool.internClass(new ClassToSymDex(classDef, filter));
            } else {
                //不需要处理的class,直接复制
                shellDexPool.internClass(classDef);
            }
        }
        DexConfig config = new DexConfig(outDir, dexFileName);


        //写入需要运行的dex
        shellDexPool.writeTo(new FileDataStore(config.getShellDexFile()));
        //写入符号dex
        nativeImplDexPool.writeTo(new FileDataStore(config.getImplDexFile()));


        final DexBackedDexFile nativeImplDexFile = DexBackedDexFile.fromInputStream(Opcodes.getDefault(),
                new BufferedInputStream(new FileInputStream(config.getImplDexFile())));

        //根据符号dex生成c代码
        try (FileWriter nativeCodeWriter = new FileWriter(config.getNativeFunctionsFile());
             FileWriter resolverWriter = new FileWriter(config.getResolverFile());
        ) {
            final ClassAnalyzer classAnalyzer = new ClassAnalyzer(originDexFile);
            JniCodeGenerator codeGenerator = new JniCodeGenerator(nativeImplDexFile,
                    classAnalyzer,
                    instructionRewriter);

            codeGenerator.generate(
                    config,
                    resolverWriter,
                    nativeCodeWriter
            );
            config.setResult(codeGenerator);
        }


        //可以不用产生头文件，直接使用extern
        /*
        DexConfig.HeaderFileAndSetupFuncName func = config.getHeaderFileAndSetupFunc();
        // 产生头文件包含导出函数,给外部调用
        try (FileWriter headerWriter = new FileWriter(func.headerFile)) {
            headerWriter.write(String.format(
                    "#include <jni.h>\n" +
                            "\n" +
                            "#ifdef __cplusplus\n" +
                            "extern \"C\" {\n" +
                            "#endif\n" +
                            "\n" +
                            "\n" +
                            "\n" +
                            "void %s(JNIEnv *env);\n" +
                            "\n" +
                            "\n" +
                            "#ifdef __cplusplus\n" +
                            "}\n" +
                            "#endif\n\n", func.setupFunctionName));
        }
        */
        return config;
    }

    //在处理过的class的static{}块最前面添加注册本地方法代码,如果不存在static{}块则新增<clinit>方法
    public static List<DexPool> injectCallRegisterNativeInsns(DexConfig config,
                                                              DexPool lastDexPool,
                                                              Set<String> mainClassSet,
                                                              int maxPoolSize) throws IOException {

        DexBackedDexFile dexNativeFile = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(),
                new BufferedInputStream(new FileInputStream(config.getShellDexFile())));

        List<DexPool> dexPools = new ArrayList<>();
        dexPools.add(lastDexPool);


        for (ClassDef classDef : dexNativeFile.getClasses()) {
            if (mainClassSet.contains(classDef.getType())) {//提前处理过的class,不用再处理
                continue;
            }
            internClass(config, lastDexPool, classDef);

            if (lastDexPool.hasOverflowed(maxPoolSize)) {
                lastDexPool = new DexPool(Opcodes.getDefault());
                dexPools.add(lastDexPool);
            }
        }
        return dexPools;
    }

    public static void internClass(DexConfig config, DexPool dexPool, ClassDef classDef) {
        final Set<String> classes = config.getHandledNativeClasses();
        final String type = classDef.getType();
        final String className = type.substring(1, type.length() - 1);
        if (classes.contains(className)) {
            final RegisterNativesCallerClassDef nativeClassDef = new RegisterNativesCallerClassDef(
                    classDef,
                    config.getOffsetFromClassName(className),
                    "L" + config.getRegisterNativesClassName() + ";",
                    config.getRegisterNativesMethodName());
            dexPool.internClass(nativeClassDef);
        } else {
            dexPool.internClass(classDef);
        }
    }

    /**
     * 在自定义application的类继承关系上增加一个新类用于加载so库
     *
     * @param dexFile
     * @param newType
     */
    public static void addApplicationClass(DexFile dexFile,
                                           DexPool newDex,
                                           final String newType) {

        ClassDef appDirectSubClassDef = null;
        List<ClassDef> parents = getClassDefParents(dexFile.getClasses(), newType, LANDROID_APP_APPLICATION);
        if (!parents.isEmpty()) {
            appDirectSubClassDef = parents.get(parents.size() - 1);
        }

        for (ClassDef classDef : dexFile.getClasses()) {
            if (classDef.equals(appDirectSubClassDef)) {
                continue;
            }
            newDex.internClass(classDef);
        }

        final LoadLibClassDef libClassDef = new LoadLibClassDef(appDirectSubClassDef,
                appDirectSubClassDef != null ? appDirectSubClassDef.getType() : newType, "nmmp");
        newDex.internClass(libClassDef);

    }

    //查找某个类所有父类
    private static List<ClassDef> getClassDefParents(final Set<? extends ClassDef> classes, String type, String rootType) {
        String tmpType = type;
        final ArrayList<ClassDef> parents = new ArrayList<>();

        //创建类型名和classDef对应关系
        final Map<String, ClassDef> classDefMap = Maps.newHashMap();
        for (ClassDef classDef : classes) {
            classDefMap.put(classDef.getType(), classDef);
        }

        while (true) {//一直查找父类直到是父类rootType返回对应的classDef
            final ClassDef classDef = classDefMap.get(tmpType);
            if (classDef == null) {
                break;
            }
            //只处理rootType的直接子类
            if (rootType.equals(classDef.getSuperclass())) {
                parents.add(classDef);
                break;
            }
            tmpType = classDef.getSuperclass();
            parents.add(classDef);
        }
        return parents;
    }
}