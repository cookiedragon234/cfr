package org.benf.cfr.reader.entities;

import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.entities.attributes.Attribute;
import org.benf.cfr.reader.entityfactories.AttributeFactory;
import org.benf.cfr.reader.entityfactories.ContiguousEntityFactory;
import org.benf.cfr.reader.util.CollectionUtils;
import org.benf.cfr.reader.util.ConfusedCFRException;
import org.benf.cfr.reader.util.MapFactory;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.functors.UnaryFunction;
import org.benf.cfr.reader.util.getopt.CFRState;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by IntelliJ IDEA.
 * User: lee
 * Date: 15/04/2011
 * Time: 18:25
 * To change this template use File | Settings | File Templates.
 */
public class ClassFile {
    // Constants
    private final long OFFSET_OF_MAGIC = 0;
    private final long OFFSET_OF_MINOR = 4;
    private final long OFFSET_OF_MAJOR = 6;
    private final long OFFSET_OF_CONSTANT_POOL_COUNT = 8;
    private final long OFFSET_OF_CONSTANT_POOL = 10;
    // From there on, we have to make up the offsets as we go, as the structure
    // is variable.


    // Members
    private final short minorVer;
    private final short majorVer;
    private final ConstantPool constantPool;
    private final Set<AccessFlag> accessFlags;
    private final List<Field> fields;
    private Map<String, Field> fieldsByName; // Lazily populated if interrogated.
    private final boolean isInterface;

    private final List<Method> methods;
    private Map<String, Method> methodsByName; // Lazily populated if interrogated.


    private final List<Attribute> attributes;
    private final ConstantPoolEntryClass thisClass;
    private final ConstantPoolEntryClass superClass;
    private final List<ConstantPoolEntryClass> interfaces;

    public ClassFile(final ByteData data) {
        int magic = data.getS4At(OFFSET_OF_MAGIC);
        if (magic != 0xCAFEBABE) throw new ConfusedCFRException("Magic != Cafebabe");

        minorVer = data.getS2At(OFFSET_OF_MINOR);
        majorVer = data.getS2At(OFFSET_OF_MAJOR);
        short constantPoolCount = data.getS2At(OFFSET_OF_CONSTANT_POOL_COUNT);
        this.constantPool = new ConstantPool(data.getOffsetData(OFFSET_OF_CONSTANT_POOL), constantPoolCount);
        final long OFFSET_OF_ACCESS_FLAGS = OFFSET_OF_CONSTANT_POOL + constantPool.getRawByteLength();
        final long OFFSET_OF_THIS_CLASS = OFFSET_OF_ACCESS_FLAGS + 2;
        final long OFFSET_OF_SUPER_CLASS = OFFSET_OF_THIS_CLASS + 2;
        final long OFFSET_OF_INTERFACES_COUNT = OFFSET_OF_SUPER_CLASS + 2;
        final long OFFSET_OF_INTERFACES = OFFSET_OF_INTERFACES_COUNT + 2;

        short numInterfaces = data.getS2At(OFFSET_OF_INTERFACES_COUNT);
        ArrayList<ConstantPoolEntryClass> tmpInterfaces = new ArrayList<ConstantPoolEntryClass>();
        final long interfacesLength = ContiguousEntityFactory.buildSized(data.getOffsetData(OFFSET_OF_INTERFACES), numInterfaces, 2, tmpInterfaces,
                new UnaryFunction<ByteData, ConstantPoolEntryClass>() {
                    @Override
                    public ConstantPoolEntryClass invoke(ByteData arg) {
                        return (ConstantPoolEntryClass) constantPool.getEntry(arg.getS2At(0));
                    }
                }
        );

        this.interfaces = tmpInterfaces;


        accessFlags = AccessFlag.build(data.getS2At(OFFSET_OF_ACCESS_FLAGS));
        this.isInterface = accessFlags.contains(AccessFlag.ACC_INTERFACE);

        final long OFFSET_OF_FIELDS_COUNT = OFFSET_OF_INTERFACES + 2 * numInterfaces;
        final long OFFSET_OF_FIELDS = OFFSET_OF_FIELDS_COUNT + 2;
        final short numFields = data.getS2At(OFFSET_OF_FIELDS_COUNT);
        ArrayList<Field> tmpFields = new ArrayList<Field>();
        tmpFields.ensureCapacity(numFields);
        final long fieldsLength = ContiguousEntityFactory.build(data.getOffsetData(OFFSET_OF_FIELDS), numFields, tmpFields,
                new UnaryFunction<ByteData, Field>() {
                    @Override
                    public Field invoke(ByteData arg) {
                        return new Field(arg, constantPool);
                    }
                });
        this.fields = tmpFields;

        final long OFFSET_OF_METHODS_COUNT = OFFSET_OF_FIELDS + fieldsLength;
        final long OFFSET_OF_METHODS = OFFSET_OF_METHODS_COUNT + 2;
        final short numMethods = data.getS2At(OFFSET_OF_METHODS_COUNT);
        ArrayList<Method> tmpMethods = new ArrayList<Method>();
        tmpMethods.ensureCapacity(numMethods);
        final long methodsLength = ContiguousEntityFactory.build(data.getOffsetData(OFFSET_OF_METHODS), numMethods, tmpMethods,
                new UnaryFunction<ByteData, Method>() {
                    @Override
                    public Method invoke(ByteData arg) {
                        return new Method(arg, ClassFile.this, constantPool);
                    }
                });
        this.methods = tmpMethods;

        final long OFFSET_OF_ATTRIBUTES_COUNT = OFFSET_OF_METHODS + methodsLength;
        final long OFFSET_OF_ATTRIBUTES = OFFSET_OF_ATTRIBUTES_COUNT + 2;
        final short numAttributes = data.getS2At(OFFSET_OF_ATTRIBUTES_COUNT);
        ArrayList<Attribute> tmpAttributes = new ArrayList<Attribute>();
        tmpAttributes.ensureCapacity(numAttributes);
        ContiguousEntityFactory.build(data.getOffsetData(OFFSET_OF_ATTRIBUTES), numAttributes, tmpAttributes,
                new UnaryFunction<ByteData, Attribute>() {
                    @Override
                    public Attribute invoke(ByteData arg) {
                        return AttributeFactory.build(arg, constantPool);
                    }
                });
        this.attributes = tmpAttributes;

        thisClass = (ConstantPoolEntryClass) constantPool.getEntry(data.getS2At(OFFSET_OF_THIS_CLASS));
//        constantPool.markClassNameUsed(constantPool.getUTF8Entry(thisClass.getNameIndex()).getValue());
        short superClassIndex = data.getS2At(OFFSET_OF_SUPER_CLASS);
        if (superClassIndex == 0) {
            superClass = null;
        } else {
            superClass = superClassIndex == 0 ? null : (ConstantPoolEntryClass) constantPool.getEntry(superClassIndex);
//            constantPool.markClassNameUsed(constantPool.getUTF8Entry(superClass.getNameIndex()).getValue());
        }
    }

    public ConstantPool getConstantPool() {
        return constantPool;
    }

    public Field getFieldByName(String name) throws NoSuchFieldException {
        if (fieldsByName == null) {
            fieldsByName = MapFactory.newMap();
            for (Field field : fields) {
                fieldsByName.put(field.getFieldName(constantPool), field);
            }
        }
        Field field = fieldsByName.get(name);
        if (field == null) throw new NoSuchFieldException(name);
        return field;
    }

    public Method getMethodByName(String name) throws NoSuchMethodException {
        if (methodsByName == null) {
            methodsByName = MapFactory.newMap();
            for (Method method : methods) {
                methodsByName.put(method.getName(), method);
            }
        }
        Method method = methodsByName.get(name);
        if (method == null) throw new NoSuchMethodException(name);
        return method;
    }

    public void analyseTop(CFRState state) {
        boolean exceptionRecovered = false;
        for (Method method : methods) {
            if (state.analyseMethod(method.getName())) {
                try {
                    method.analyse(state);
                } catch (Exception e) {
                    System.out.println("Exception analysing " + method.getName());
                    System.out.println(e);
                    for (StackTraceElement s : e.getStackTrace()) {
                        System.out.println(s);
                    }
                    exceptionRecovered = true;
                }
            }
        }
        if (exceptionRecovered) throw new RuntimeException("Failed to analyse file");
    }

    public JavaTypeInstance getClassType() {
        return thisClass.getTypeInstance(constantPool);
    }

    private static final AccessFlag[] dumpableAccessFlagsInterface = new AccessFlag[]{
            AccessFlag.ACC_PUBLIC, AccessFlag.ACC_PRIVATE, AccessFlag.ACC_PROTECTED, AccessFlag.ACC_STATIC, AccessFlag.ACC_FINAL
    };
    private static final AccessFlag[] dumpableAccessFlagsClass = new AccessFlag[]{
            AccessFlag.ACC_PUBLIC, AccessFlag.ACC_PRIVATE, AccessFlag.ACC_PROTECTED, AccessFlag.ACC_STATIC, AccessFlag.ACC_FINAL, AccessFlag.ACC_ABSTRACT
    };

    /*
    * We don't want to just dump the access flags.
    *
    * They contain 'super', 'interface' etc which we'd never want to dump
    * and 'abstract', which we'd not dump for an interface.
    */
    String dumpAccessFlags(AccessFlag[] dumpableAccessFlags) {
        StringBuilder sb = new StringBuilder();

        for (AccessFlag accessFlag : dumpableAccessFlags) {
            if (accessFlags.contains(accessFlag)) sb.append(accessFlag).append(' ');
        }
        return sb.toString();
    }

    private void dumpInterfaceHeader(Dumper d) {
        StringBuilder sb = new StringBuilder();
        sb.append(dumpAccessFlags(dumpableAccessFlagsInterface));
        sb.append("interface ").append(thisClass.getTypeInstance(constantPool)).append("\n");
        d.print(sb.toString());
        if (!interfaces.isEmpty()) {
            d.print("extends ");
            int size = interfaces.size();
            for (int x = 0; x < size; ++x) {
                ConstantPoolEntryClass iface = interfaces.get(x);
                d.print("" + iface.getTypeInstance(constantPool) + (x < (size - 1) ? ",\n" : "\n"));
            }
        }
        d.removePendingCarriageReturn();
    }

    private void dumpClassHeader(Dumper d) {
        StringBuilder sb = new StringBuilder();
        sb.append(dumpAccessFlags(dumpableAccessFlagsClass));
        sb.append("class ").append(thisClass.getTypeInstance(constantPool)).append("\n");
        d.print(sb.toString());
        if (superClass != null) {
            d.print("extends " + superClass.getTypeInstance(constantPool) + "\n");
        }
        if (!interfaces.isEmpty()) {
            d.print("implements ");
            int size = interfaces.size();
            for (int x = 0; x < size; ++x) {
                ConstantPoolEntryClass iface = interfaces.get(x);
                d.print("" + iface.getTypeInstance(constantPool) + (x < (size - 1) ? ",\n" : "\n"));
            }
        }
        d.removePendingCarriageReturn();
    }

    public void dumpAsInterface(Dumper d) {
        d.line();
        d.print("// Imports\n");
        constantPool.dumpImports(d);
        dumpInterfaceHeader(d);
        d.print("{\n");

        if (!methods.isEmpty()) {
            d.print("// Methods\n");
            for (Method meth : methods) {
                d.newln();
                d.print(meth.getSignatureText(false) + ";");
            }
        }
        d.newln();
        d.print("}\n");

    }

    public void dumpAsClass(Dumper d) {
        d.line();
        d.print("// Imports\n");
        constantPool.dumpImports(d);
        dumpClassHeader(d);
        d.print("{\n");

        if (!fields.isEmpty()) {
            d.print("// Fields\n");
            for (Field field : fields) {
                field.dump(d, constantPool);
            }
        }
//        d.print("// Attributes\n");
//        for (Attribute attr : attributes) {
//            d.newln();
//            attr.dump(d, constantPool);
//        }
//        d.line();
        if (!methods.isEmpty()) {
            d.print("// Methods\n");
            for (Method meth : methods) {
                d.newln();
                meth.dump(d, constantPool);
            }
        }
        d.newln();
        d.print("}\n");

    }

    public void Dump(Dumper d) {
        if (isInterface) {
            dumpAsInterface(d);
        } else {
            dumpAsClass(d);
        }
    }
}
