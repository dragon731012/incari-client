package incari.tools.webbuild;

import incari.tools.survey.Finding;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHierarchy;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.FieldReference;
import org.teavm.model.Instruction;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.TryCatchBlock;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.AbstractInstructionVisitor;
import org.teavm.model.instructions.CastInstruction;
import org.teavm.model.instructions.ClassConstantInstruction;
import org.teavm.model.instructions.ConstructArrayInstruction;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.ConstructMultiArrayInstruction;
import org.teavm.model.instructions.DoubleConstantInstruction;
import org.teavm.model.instructions.FloatConstantInstruction;
import org.teavm.model.instructions.GetFieldInstruction;
import org.teavm.model.instructions.InitClassInstruction;
import org.teavm.model.instructions.IntegerConstantInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.IsInstanceInstruction;
import org.teavm.model.instructions.LongConstantInstruction;
import org.teavm.model.instructions.NullConstantInstruction;
import org.teavm.model.instructions.PutFieldInstruction;
import org.teavm.model.instructions.StringConstantInstruction;

/**
 * Removes references to missing symbols that {@link Removals} says go, as TeaVM loads each class.
 *
 * <p>A reference to be thrown becomes a call to {@code incari.classlib.Removed.reference}, which
 * throws; a stripped one (a deleted feature) just goes. Either way, whatever value the instruction
 * produced becomes a default (null, zero), so the code after it stays well-formed, and a stripped
 * feature's calls on that null are stripped too. Three cases never throw: {@code instanceof} a removed
 * class is false (nothing can be one), its class literal is a class nothing is an instance of, and a
 * {@code catch} of one catches a type nothing throws. A
 * missing supertype is dropped (an interface) or becomes {@code Object} (a superclass).
 *
 * <p>TeaVM instantiates transformers by class name, so {@link Main} hands the rules over through
 * {@link #removals}. One compile runs at a time.
 */
public final class RemovalTransformer implements ClassHolderTransformer {

    static volatile @Nullable Removals removals;

    private static final String REMOVED = "incari.classlib.Removed";
    private static final MethodReference REFERENCE =
            new MethodReference(REMOVED, "reference", ValueType.object("java.lang.String"), ValueType.VOID);
    private static final String NEVER = REMOVED + "$Never";
    private static final java.util.regex.Pattern GENERATED =
            java.util.regex.Pattern.compile("\\$lambda\\$_\\d+_\\d+$|\\$_AtomicUpdater\\$$|\\$\\$_impl$");
    private static final String ABSENT = REMOVED + "$Absent";

    /**
     * Whether TeaVM generates this class: for a lambda ({@code Owner$method$lambda$_1_0}), for
     * an atomic field updater ({@code Owner$field$_AtomicUpdater$}, which replaces
     * {@code AtomicIntegerFieldUpdater.newUpdater} and its kind), or for an annotation read at run
     * time ({@code Annotation$$_impl}). It does so after transformers run, so the class source
     * doesn't have it yet; it isn't missing. Treating updaters as missing broke Netty's reference
     * counts, so every buffer; treating annotation classes as missing broke Netty's
     * {@code @Sharable} check, so every pipeline.
     */
    static boolean isGenerated(String className) {
        return GENERATED.matcher(className).find();
    }

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        Removals current = removals;
        if (current == null || cls.getName().startsWith(REMOVED)) {
            return;
        }
        new Pass(cls, context.getHierarchy(), current).run();
    }

    private static final class Pass extends AbstractInstructionVisitor {
        private final ClassHolder cls;
        private final ClassHierarchy hierarchy;
        private final Removals removals;

        Pass(ClassHolder cls, ClassHierarchy hierarchy, Removals removals) {
            this.cls = cls;
            this.hierarchy = hierarchy;
            this.removals = removals;
        }

        void run() {
            removeSupertypes();
            for (MethodHolder method : cls.getMethods()) {
                Program program = method.getProgram();
                if (program == null) {
                    continue;
                }
                for (int i = 0; i < program.basicBlockCount(); i++) {
                    BasicBlock block = program.basicBlockAt(i);
                    List<Instruction> instructions = new ArrayList<>();
                    block.forEach(instructions::add);
                    for (Instruction instruction : instructions) {
                        instruction.acceptVisitor(this);
                    }
                    for (TryCatchBlock tryCatch : block.getTryCatchBlocks()) {
                        String type = tryCatch.getExceptionType();
                        if (type != null && removedClass(type) != null) {
                            tryCatch.setExceptionType(NEVER);
                        }
                    }
                }
            }
        }

        private void removeSupertypes() {
            String parent = cls.getParent();
            if (parent != null && isMissing(parent) && removedSupertype(parent) != null) {
                cls.setParent("java.lang.Object");
            }
            for (String iface : List.copyOf(cls.getInterfaces())) {
                if (isMissing(iface) && removedSupertype(iface) != null) {
                    cls.getInterfaces().remove(iface);
                }
            }
        }

        private Removals.@Nullable Removal removedSupertype(String className) {
            return removals.removal(Finding.Kind.MISSING_SUPERTYPE, className, cls.getName());
        }

        // --- What's missing --------------------------------------------------------------------

        private boolean isMissing(String className) {
            return !isGenerated(className) && hierarchy.getClassSource().get(className) == null;
        }

        /** How a reference to this class goes, or null if the class exists or stays an error. */
        private Removals.@Nullable Removal removedClass(String className) {
            return isMissing(className)
                    ? removals.removal(removals.missingClassKind(className), className, cls.getName())
                    : null;
        }

        private Removals.@Nullable Removal removedType(ValueType type) {
            ValueType item = type;
            while (item instanceof ValueType.Array array) {
                item = array.getItemType();
            }
            return item instanceof ValueType.Object object ? removedClass(object.getClassName()) : null;
        }

        private Removals.@Nullable Removal removedMethod(MethodReference method) {
            String owner = method.getClassName();
            if (isGenerated(owner)) {
                return null;
            }
            String symbol = owner + "#" + method.getName();
            if (isMissing(owner)) {
                return removals.removal(removals.missingClassKind(owner), symbol, cls.getName());
            }
            return hierarchy.resolve(method) == null && !providedLater(method)
                    ? removals.removal(Finding.Kind.MISSING_METHOD, symbol, cls.getName())
                    : null;
        }

        private Removals.@Nullable Removal removedField(FieldReference field) {
            String owner = field.getClassName();
            if (isGenerated(owner)) {
                return null;
            }
            String symbol = owner + "#" + field.getFieldName();
            if (isMissing(owner)) {
                return removals.removal(removals.missingClassKind(owner), symbol, cls.getName());
            }
            return hasField(owner, field.getFieldName())
                    ? null
                    : removals.removal(Finding.Kind.MISSING_FIELD, symbol, cls.getName());
        }

        /**
         * Whether the build provides a method the hierarchy doesn't show. The hierarchy reads
         * classes before this compile's transformers run: a substitute's signatures still name
         * other substitutes by their {@code T} names, and additions aren't merged in yet.
         */
        private boolean providedLater(MethodReference method) {
            MethodDescriptor wanted = method.getDescriptor();
            java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            pending.add(method.getClassName());
            while (!pending.isEmpty()) {
                String name = pending.removeFirst();
                if (!seen.add(name)) {
                    continue;
                }
                if (hasAddition(name, wanted)) {
                    return true;
                }
                org.teavm.model.ClassReader reader = hierarchy.getClassSource().get(name);
                if (reader == null) {
                    continue;
                }
                for (org.teavm.model.MethodReader candidate : reader.getMethods()) {
                    if (candidate.getName().equals(wanted.getName())
                            && AdditionsTransformer.originalNames(candidate.getDescriptor())
                                    .equals(wanted)) {
                        return true;
                    }
                }
                if (reader.getParent() != null) {
                    pending.add(reader.getParent());
                }
                pending.addAll(reader.getInterfaces());
            }
            return false;
        }

        /** Whether {@code className}'s additions class adds {@code wanted}, static or instance. */
        private boolean hasAddition(String className, MethodDescriptor wanted) {
            org.teavm.model.ClassReader additions =
                    hierarchy.getClassSource().get(AdditionsTransformer.PACKAGE + "." + className + "Additions");
            if (additions == null) {
                return false;
            }
            for (org.teavm.model.MethodReader addition : additions.getMethods()) {
                MethodDescriptor added = AdditionsTransformer.originalNames(addition.getDescriptor());
                if (addition.getAnnotations().get(AdditionsTransformer.CONSTRUCTOR) != null) {
                    if (AdditionsTransformer.asConstructor(added).equals(wanted)) {
                        return true;
                    }
                    continue;
                }
                if (!addition.getName().equals(wanted.getName())) {
                    continue;
                }
                if (addition.getAnnotations().get(AdditionsTransformer.INSTANCE_METHOD) != null) {
                    added = AdditionsTransformer.dropReceiver(added);
                }
                if (added.equals(wanted)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the class or a supertype declares the field. Not {@code ClassHierarchy.resolve}:
         * in TeaVM it only looks at supertypes, never the class's own fields.
         */
        private boolean hasField(String className, String fieldName) {
            java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            pending.add(className);
            while (!pending.isEmpty()) {
                String name = pending.removeFirst();
                if (!seen.add(name)) {
                    continue;
                }
                org.teavm.model.ClassReader reader = hierarchy.getClassSource().get(name);
                if (reader == null) {
                    continue;
                }
                if (reader.getField(fieldName) != null) {
                    return true;
                }
                if (reader.getParent() != null) {
                    pending.add(reader.getParent());
                }
                pending.addAll(reader.getInterfaces());
            }
            return false;
        }

        // --- Instructions ----------------------------------------------------------------------

        @Override
        public void visit(InvokeInstruction insn) {
            MethodReference method = insn.getMethod();
            Removals.Removal removal = removedMethod(method);
            if (removal != null) {
                replace(
                        insn,
                        removal,
                        method.getClassName() + "#" + method.getName(),
                        insn.getReceiver(),
                        method.getReturnType());
            }
        }

        @Override
        public void visit(ConstructInstruction insn) {
            Removals.Removal removal = removedClass(insn.getType());
            if (removal != null) {
                replace(insn, removal, insn.getType(), insn.getReceiver(), ValueType.object(insn.getType()));
            }
        }

        @Override
        public void visit(GetFieldInstruction insn) {
            Removals.Removal removal = removedField(insn.getField());
            if (removal != null) {
                replace(insn, removal, insn.getField().toString(), insn.getReceiver(), insn.getFieldType());
            }
        }

        @Override
        public void visit(PutFieldInstruction insn) {
            Removals.Removal removal = removedField(insn.getField());
            if (removal != null) {
                replace(insn, removal, insn.getField().toString(), null, null);
            }
        }

        @Override
        public void visit(InitClassInstruction insn) {
            Removals.Removal removal = removedClass(insn.getClassName());
            if (removal != null) {
                replace(insn, removal, insn.getClassName(), null, null);
            }
        }

        @Override
        public void visit(CastInstruction insn) {
            Removals.Removal removal = removedType(insn.getTargetType());
            if (removal != null) {
                replace(insn, removal, insn.getTargetType().toString(), insn.getReceiver(), insn.getTargetType());
            }
        }

        @Override
        public void visit(ClassConstantInstruction insn) {
            if (removedType(insn.getConstant()) != null) {
                // Libraries register handlers by class literal (Gson's adapter for InetAddress).
                // A class nothing can be an instance of keeps those registrations harmless.
                insn.setConstant(absent(insn.getConstant()));
            }
        }

        private static ValueType absent(ValueType type) {
            return type instanceof ValueType.Array array
                    ? ValueType.arrayOf(absent(array.getItemType()))
                    : ValueType.object(ABSENT);
        }

        @Override
        public void visit(ConstructArrayInstruction insn) {
            Removals.Removal removal = removedType(insn.getItemType());
            if (removal != null) {
                replace(
                        insn,
                        removal,
                        insn.getItemType().toString(),
                        insn.getReceiver(),
                        ValueType.arrayOf(insn.getItemType()));
            }
        }

        @Override
        public void visit(ConstructMultiArrayInstruction insn) {
            Removals.Removal removal = removedType(insn.getItemType());
            if (removal != null) {
                replace(insn, removal, insn.getItemType().toString(), insn.getReceiver(), insn.getItemType());
            }
        }

        @Override
        public void visit(IsInstanceInstruction insn) {
            if (removedType(insn.getType()) != null) {
                // Nothing is an instance of a class that isn't there.
                IntegerConstantInstruction no = new IntegerConstantInstruction();
                no.setConstant(0);
                no.setReceiver(insn.getReceiver());
                no.setLocation(insn.getLocation());
                insn.replace(no);
            }
        }

        /**
         * Removes {@code insn}: throws where it was (unless stripped), then gives its result, if
         * any, a default value.
         */
        private void replace(
                Instruction insn,
                Removals.Removal removal,
                String symbol,
                @Nullable Variable result,
                @Nullable ValueType type) {
            if (removal == Removals.Removal.THROW) {
                Program program = insn.getProgram();
                StringConstantInstruction name = new StringConstantInstruction();
                name.setConstant(symbol);
                name.setReceiver(program.createVariable());
                name.setLocation(insn.getLocation());
                InvokeInstruction fail = new InvokeInstruction();
                fail.setType(InvocationType.SPECIAL);
                fail.setMethod(REFERENCE);
                fail.setArguments(name.getReceiver());
                fail.setLocation(insn.getLocation());
                insn.insertPrevious(name);
                insn.insertPrevious(fail);
            }
            if (result == null || type == null) {
                insn.delete();
                return;
            }
            if (type instanceof ValueType.Primitive) {
                Instruction value = defaultValue(result, type);
                value.setLocation(insn.getLocation());
                insn.replace(value);
                return;
            }
            // A typed null: an untyped one leaves TeaVM to infer the variable's type from its other
            // uses, which can be a supertype the rest of the code can't pass on (a ByteBuffer from a
            // removed allocator typed as Buffer). Only a class that exists can be cast to.
            if (!isRemovedOrMissing(type)) {
                NullConstantInstruction none = new NullConstantInstruction();
                none.setReceiver(insn.getProgram().createVariable());
                none.setLocation(insn.getLocation());
                insn.insertPrevious(none);
                CastInstruction typed = new CastInstruction();
                typed.setValue(none.getReceiver());
                typed.setTargetType(type);
                typed.setReceiver(result);
                typed.setLocation(insn.getLocation());
                insn.replace(typed);
                return;
            }
            Instruction value = defaultValue(result, type);
            value.setLocation(insn.getLocation());
            insn.replace(value);
        }

        private boolean isRemovedOrMissing(ValueType type) {
            ValueType item = type;
            while (item instanceof ValueType.Array array) {
                item = array.getItemType();
            }
            return item instanceof ValueType.Object object && isMissing(object.getClassName());
        }

        private static Instruction defaultValue(Variable receiver, ValueType type) {
            if (!(type instanceof ValueType.Primitive primitive)) {
                NullConstantInstruction none = new NullConstantInstruction();
                none.setReceiver(receiver);
                return none;
            }
            return switch (primitive.getKind()) {
                case LONG -> {
                    LongConstantInstruction zero = new LongConstantInstruction();
                    zero.setReceiver(receiver);
                    yield zero;
                }
                case FLOAT -> {
                    FloatConstantInstruction zero = new FloatConstantInstruction();
                    zero.setReceiver(receiver);
                    yield zero;
                }
                case DOUBLE -> {
                    DoubleConstantInstruction zero = new DoubleConstantInstruction();
                    zero.setReceiver(receiver);
                    yield zero;
                }
                default -> {
                    IntegerConstantInstruction zero = new IntegerConstantInstruction();
                    zero.setReceiver(receiver);
                    yield zero;
                }
            };
        }
    }
}
