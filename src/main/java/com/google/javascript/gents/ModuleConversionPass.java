package com.google.javascript.gents;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.javascript.gents.CollectModuleMetadata.FileModule;
import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeTraversal.AbstractPreOrderCallback;
import com.google.javascript.jscomp.NodeUtil;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts Closure-style modules into TypeScript (ES6) modules (not namespaces). All module
 * metadata must be populated before running this CompilerPass.
 */
public final class ModuleConversionPass implements CompilerPass {

  private static final String EXPORTS = "exports";

  private final AbstractCompiler compiler;
  private final PathUtil pathUtil;
  private final NameUtil nameUtil;
  private final NodeComments nodeComments;

  private final Map<String, FileModule> fileToModule;
  private final Map<String, FileModule> namespaceToModule;

  /**
   * Map of metadata about a potential export to the node that should be exported.
   *
   * <p>For example, in the case below, the {@code Node} would point to {@code class Foo}.
   * <code><pre>
   * class Foo {}
   * exports {Foo}
   * </pre><code>
   */
  private final Map<ExportedSymbol, Node> exportsToNodes = new HashMap<>();

  // Used for rewriting usages of imported symbols
  /** fileName, namespace -> local name */
  private final Table<String, String, String> valueRewrite = HashBasedTable.create();
  /** fileName, namespace -> local name */
  private final Table<String, String, String> typeRewrite = HashBasedTable.create();

  private final String alreadyConvertedPrefix;

  public Table<String, String, String> getTypeRewrite() {
    return typeRewrite;
  }

  public ModuleConversionPass(
      AbstractCompiler compiler,
      PathUtil pathUtil,
      NameUtil nameUtil,
      Map<String, FileModule> fileToModule,
      Map<String, FileModule> namespaceToModule,
      NodeComments nodeComments,
      String alreadyConvertedPrefix) {
    this.compiler = compiler;
    this.pathUtil = pathUtil;
    this.nameUtil = nameUtil;
    this.nodeComments = nodeComments;

    this.fileToModule = fileToModule;
    this.namespaceToModule = namespaceToModule;
    this.alreadyConvertedPrefix = alreadyConvertedPrefix;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverseEs6(compiler, root, new ModuleExportConverter());
    NodeTraversal.traverseEs6(compiler, root, new ModuleImportConverter());
    NodeTraversal.traverseEs6(compiler, root, new ModuleImportRewriter());
  }

  /**
   * Converts "exports" assignments into TypeScript export statements. This also builds a map of all
   * the declared modules.
   */
  private class ModuleExportConverter extends AbstractTopLevelCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      String fileName = n.getSourceFileName();
      if (n.isScript()) {
        if (fileToModule.containsKey(fileName)) {
          // Module is declared purely for side effects
          FileModule module = fileToModule.get(fileName);
          if (!module.hasImports() && !module.hasExports()) {
            // export {};
            Node commentNode = new Node(Token.EMPTY);
            commentNode.useSourceInfoFrom(n);
            nodeComments.addComment(
                commentNode,
                "\n// gents: force this file to be an ES6 module (no imports or exports)");

            Node exportNode = new Node(Token.EXPORT, new Node(Token.EXPORT_SPECS, commentNode));
            commentNode.useSourceInfoFromForTree(n);

            if (n.hasChildren() && n.getFirstChild().isModuleBody()) {
              n.getFirstChild().addChildToFront(exportNode);
            } else {
              n.addChildToFront(exportNode);
            }
          }
        }
      }

      if (!n.isExprResult()) {
        if (n.isConst() || n.isClass() || n.isFunction()) {
          collectMetdataForExports(n, fileName);
        }
        return;
      }

      Node child = n.getFirstChild();
      switch (child.getToken()) {
        case CALL:
          String callName = child.getFirstChild().getQualifiedName();
          if ("goog.module".equals(callName) || "goog.provide".equals(callName)) {
            // Remove the goog.module and goog.provide calls.
            if (nodeComments.hasComment(n)) {
              nodeComments.replaceWithComment(n, new Node(Token.EMPTY));
            } else {
              compiler.reportChangeToEnclosingScope(n);
              n.detach();
            }
          }
          break;
        case GETPROP:
          {
            JSDocInfo jsdoc = NodeUtil.getBestJSDocInfo(child);
            if (jsdoc == null || !jsdoc.containsTypeDefinition()) {
              // GETPROPs on the root level are only exports for @typedefs
              break;
            }
            if (!fileToModule.containsKey(fileName)) {
              break;
            }
            FileModule module = fileToModule.get(fileName);
            Map<String, String> symbols = module.exportedNamespacesToSymbols;
            String exportedNamespace = nameUtil.findLongestNamePrefix(child, symbols.keySet());
            if (exportedNamespace != null) {
              String localName = symbols.get(exportedNamespace);
              Node export =
                  new Node(
                      Token.EXPORT,
                      new Node(
                          Token.EXPORT_SPECS,
                          new Node(Token.EXPORT_SPEC, Node.newString(Token.NAME, localName))));
              export.useSourceInfoFromForTree(child);
              parent.addChildAfter(export, n);
              // Registers symbol for rewriting local uses.
              registerLocalSymbol(
                  child.getSourceFileName(), exportedNamespace, exportedNamespace, localName);
            }
            break;
          }
        case ASSIGN:
          if (!fileToModule.containsKey(fileName)) {
            break;
          }
          FileModule module = fileToModule.get(fileName);
          Node lhs = child.getFirstChild();
          Map<String, String> symbols = module.exportedNamespacesToSymbols;

          // We export the longest valid prefix
          String exportedNamespace = nameUtil.findLongestNamePrefix(lhs, symbols.keySet());
          if (exportedNamespace != null) {
            convertExportAssignment(
                child, exportedNamespace, symbols.get(exportedNamespace), fileName);
            // Registers symbol for rewriting local uses
            registerLocalSymbol(
                child.getSourceFileName(),
                exportedNamespace,
                exportedNamespace,
                symbols.get(exportedNamespace));
          }
          break;
        default:
          break;
      }
    }

    private void collectMetdataForExports(Node namedNode, String fileName) {
      if (!fileToModule.containsKey(fileName)) {
        return;
      }

      String nodeName = namedNode.getFirstChild().getQualifiedName();
      if (nodeName == null) {
        return;
      }
      exportsToNodes.put(ExportedSymbol.of(fileName, nodeName, nodeName), namedNode);
    }
  }

  /** Converts goog.require statements into TypeScript import statements. */
  private class ModuleImportConverter extends AbstractTopLevelCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (NodeUtil.isNameDeclaration(n)) {
        Node node = n.getFirstFirstChild();
        if (node == null) {
          return;
        }

        // var x = goog.require(...);
        if (node.isCall() && node.getFirstChild().matchesQualifiedName("goog.require")) {
          Node callNode = node;
          String requiredNamespace = callNode.getLastChild().getString();
          String localName = n.getFirstChild().getQualifiedName();
          // convertRequireToDefaultImportStatements(n, localName, requiredNamespace);
          convertRequireToImportStatements(
              n, Collections.singletonList(localName), requiredNamespace, false);
          return;
        }

        // var {foo, bar} = goog.require(...);
        if (node.isObjectPattern()
            && node.getNext().getFirstChild() != null
            && node.getNext().getFirstChild().matchesQualifiedName("goog.require")) {
          Node importedNode = node.getFirstChild();
          List<String> namedExports = new ArrayList();
          while (importedNode != null) {
            namedExports.add(importedNode.getString());
            importedNode = importedNode.getNext();
          }
          String requiredNamespace = node.getNext().getFirstChild().getNext().getString();
          requiredNamespace += "." + namedExports.get(0);
          convertRequireToImportStatements(n, namedExports, requiredNamespace, true);
          return;
        }
      } else if (n.isExprResult()) {
        // goog.require(...);
        Node callNode = n.getFirstChild();
        if (callNode == null || !callNode.isCall()) {
          return;
        }
        if (callNode.getFirstChild().matchesQualifiedName("goog.require")) {
          String requiredNamespace = callNode.getLastChild().getString();
          convertRequireToImportStatements(
              n, Collections.singletonList(requiredNamespace), requiredNamespace, false);
          return;
        }
      }
    }
  }

  /** Rewrites variable names used in the file to correspond to the newly imported symbols. */
  private class ModuleImportRewriter extends AbstractPreOrderCallback {
    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      // Rewrite all imported variable name usages
      if (n.isName() || n.isGetProp()) {
        if (!valueRewrite.containsRow(n.getSourceFileName())) {
          return true;
        }

        Map<String, String> rewriteMap = valueRewrite.rowMap().get(n.getSourceFileName());
        String importedNamespace = nameUtil.findLongestNamePrefix(n, rewriteMap.keySet());
        if (importedNamespace != null) {
          nameUtil.replacePrefixInName(n, importedNamespace, rewriteMap.get(importedNamespace));
          return false;
        }
      }
      return true;
    }
  }

  class ModuleImport {
    private Node originalImportNode;

    private List<String> localNames;

    private String requiredNamespace;

    private boolean isDestruringImport;

    private FileModule module;

    private String referencedFile;

    private String moduleSuffix;

    private boolean imported;

    private ModuleImport(Node originalImportNode, List<String> localNames, String requiredNamespace, boolean isDestruringImport) {
      this.originalImportNode = originalImportNode;
      this.localNames = localNames;
      this.requiredNamespace = requiredNamespace;
      this.isDestruringImport = isDestruringImport;
      this.module = namespaceToModule.get(requiredNamespace);
      this.referencedFile = pathUtil.getImportPath(originalImportNode.getSourceFileName(), module.file);
      this.moduleSuffix = nameUtil.lastStepOfName((requiredNamespace));
      this.imported = false;
    }

    private boolean invalidImportLHS() {
      if (!isDestruringImport && localNames.size() != 1) {
        compiler.report(
            JSError.make(
                originalImportNode,
                GentsErrorManager.GENTS_MODULE_PASS_ERROR,
                String.format(
                    "Non destructuring imports should have only one local name, got [%s]",
                    String.join(", ", localNames))));
        return false;
      }
      return true;
    }

    private String getBackupName(String localName) {
      return moduleSuffix.equals(localName) ? moduleSuffix + "Exports" : moduleSuffix;
    }

    private boolean isAlreadyConverted() {
      return requiredNamespace.startsWith(alreadyConvertedPrefix + ".");
    }

    private boolean moduleExists() {
      if (namespaceToModule.containsKey(requiredNamespace)){
        return true;
      }

      if (isAlreadyConverted()) {
        return true;
      }

      return false;
    }
  }

  void convertRequireToDefaultImportStatements(Node n, String fullLocalName, String requiredNamespace) {}

  void maybeConvertRequireForAlreadyConverted(){

  }

  /**
   * Converts a Closure goog.require call into a TypeScript import statement.
   *
   * <p>The resulting node is dependent on the exports by the module being imported:
   *
   * <pre>
   *   import localName from "goog:old.namespace.syntax";
   *   import {A as localName, B} from "./valueExports";
   *   import * as localName from "./objectExports";
   *   import "./sideEffectsOnly"
   * </pre>
   */
  void convertRequireToImportStatements(Node n, ModuleImport moduleImport) {
    if (!moduleImport.moduleExists()) {
      compiler.report(
          JSError.make(
              n,
              GentsErrorManager.GENTS_MODULE_PASS_ERROR,
              String.format("Module %s does not exist.", moduleImport.requiredNamespace)));
      return;
    }

    if (moduleImport.isAlreadyConverted()) {
      // we cannot use referencedFile here, because usually it points to the ES5 js file that is
      // the output of TS, and not the original source TS file.
      // However, we can reverse map the goog.module name to a file name.
      // TODO(rado): sync this better with the mapping done in tsickle.
      String originalPath =
          requiredNamespace.replace(alreadyConvertedPrefix + ".", "").replace(".", "/");
      // requiredNamespace is not always precisely the string inside "goog.require(...)", we
      // have to strip suffixes added earlier.
      for (int i = 0; i < fullLocalNames.size(); i++) {
        String localName = localNames.get(i);
        String fullLocalName = fullLocalNames.get(i);

        if (moduleSuffix.equals(localName) && !fullLocalName.equals(requiredNamespace)) {
          originalPath = originalPath.replaceAll("/" + localName + "$", "");
        }
        convertRequireForAlreadyConverted(
            n, fullLocalName, pathUtil.getImportPath(n.getSourceFileName(), originalPath));
      }
      return;
    }

    String referencedFile = pathUtil.getImportPath(n.getSourceFileName(), module.file);

    // This is a namespace/module that is kept as JavaScript
    if (module.shouldUseOldSyntax()) {
      Node nodeToImport = null;
      // For destructuring imports use `import {foo} from 'goog:bar';`
      if (isDestructuringImports) {
        nodeToImport = new Node(Token.OBJECTLIT);
        for (String localName : localNames) {
          nodeToImport.addChildToBack(Node.newString(Token.STRING_KEY, localName));
        }
        // For non destructuring imports, it is safe to assume there's only one localName
      } else if (module.hasDefaultExport) {
        // If it has a default export then use `import foo from 'goog:bar';`
        nodeToImport = Node.newString(Token.NAME, localNames.get(0));
      } else {
        // If it doesn't have a default export then use `import * as foo from 'goog:bar';`
        nodeToImport = Node.newString(Token.IMPORT_STAR, localNames.get(0));
      }
      String importString = requiredNamespace;
      if (isDestructuringImports) {
        importString = requiredNamespace.replaceAll("." + localNames.get(0), "");
      }
      Node importNode =
          new Node(Token.IMPORT, IR.empty(), nodeToImport, Node.newString("goog:" + importString));
      nodeComments.replaceWithComment(n, importNode);
      compiler.reportChangeToEnclosingScope(importNode);

      for (int i = 0; i < fullLocalNames.size(); i++) {
        registerLocalSymbol(
            n.getSourceFileName(), fullLocalNames.get(i), requiredNamespace, localNames.get(i));
      }
      return;
    }

    boolean imported = false;
    if (module.importedNamespacesToSymbols.containsKey(requiredNamespace)) {
      // import {value as localName} from "./file"
      Node importSpec = new Node(Token.IMPORT_SPEC, IR.name(moduleSuffix));
      // import {a as b} only when a =/= b
      for (String localName : localNames) {
        if (!moduleSuffix.equals(localName)) {
          importSpec.addChildToBack(IR.name(localName));
        }
      }

      Node importNode =
          new Node(
              Token.IMPORT,
              IR.empty(),
              new Node(Token.IMPORT_SPECS, importSpec),
              Node.newString(referencedFile));
      importNode.useSourceInfoFromForTree(n);
      n.getParent().addChildBefore(importNode, n);
      nodeComments.moveComment(n, importNode);
      imported = true;

      for (int i = 0; i < fullLocalNames.size(); i++) {
        registerLocalSymbol(
            n.getSourceFileName(), fullLocalNames.get(i), requiredNamespace, localNames.get(i));
        // Switch to back up name if necessary
        localNames.set(i, backupNames.get(0));
      }
    }

    if (module.providesObjectChildren.get(requiredNamespace).size() > 0) {
      // import * as var from "./file"
      Node importNode =
          new Node(
              Token.IMPORT,
              IR.empty(),
              Node.newString(Token.IMPORT_STAR, localNames.get(0)),
              Node.newString(referencedFile));
      n.getParent().addChildBefore(importNode, n);
      importNode.useSourceInfoFromForTree(n);
      nodeComments.moveComment(n, importNode);
      imported = true;

      for (String child : module.providesObjectChildren.get(requiredNamespace)) {
        if (!valueRewrite.contains(n.getSourceFileName(), child)) {
          String fileName = n.getSourceFileName();
          registerLocalSymbol(
              fileName,
              fullLocalNames.get(0) + '.' + child,
              requiredNamespace + '.' + child,
              localNames.get(0) + '.' + child);
        }
      }
    }

    if (!imported) {
      // side effects only
      Node importNode =
          new Node(Token.IMPORT, IR.empty(), IR.empty(), Node.newString(referencedFile));
      importNode.useSourceInfoFromForTree(n);
      n.getParent().addChildBefore(importNode, n);
      nodeComments.moveComment(n, importNode);
    }

    compiler.reportChangeToEnclosingScope(n);
    n.detach();
  }

  private void convertRequireForAlreadyConverted(
      Node n, String fullLocalName, String referencedFile) {
    // case of side-effectful imports.
    // goog.require('...'); -> import '...';
    Node importSpec = IR.empty();
    if (n.getFirstChild() != null && n.getFirstChild().isDestructuringLhs()) {
      // case of destructuring imports.
      // only single object is supported - https://github.com/angular/clutz/issues/392
      // const {A} = goog.require('...'); -> import {A} from '...';
      importSpec =
          new Node(Token.IMPORT_SPECS, new Node(Token.IMPORT_SPEC, IR.name(fullLocalName)));
    } else if (n.getFirstChild() != null && n.getFirstChild().isName()) {
      // case of full module import.
      // const A = goog.require('...'); -> import * as A from '...';
      importSpec = Node.newString(Token.IMPORT_STAR, fullLocalName);
    }
    Node importNode =
        new Node(Token.IMPORT, IR.empty(), importSpec, Node.newString(referencedFile));
    nodeComments.replaceWithComment(n, importNode);
    compiler.reportChangeToEnclosingScope(importNode);
  }

  /**
   * Converts a Closure assignment on a goog.module or goog.provide namespace into a TypeScript
   * export statement. This method should only be called on a node within a module.
   *
   * @param assign Assignment node
   * @param exportedNamespace The prefix of the assignment name that we are exporting
   * @param exportedSymbol The symbol that we want to export from the file For example,
   *     convertExportAssignment(pre.fix = ..., "pre.fix", "name") <-> export const name = ...
   *     convertExportAssignment(pre.fix.foo = ..., "pre.fix", "name") <-> name.foo = ...
   */
  void convertExportAssignment(
      Node assign, String exportedNamespace, String exportedSymbol, String fileName) {
    checkState(assign.isAssign());
    checkState(assign.getParent().isExprResult());
    Node grandParent = assign.getGrandparent();
    checkState(
        grandParent.isScript() || grandParent.isModuleBody(),
        "export assignment must be in top level script or module body");

    Node exprNode = assign.getParent();
    Node lhs = assign.getFirstChild();
    Node rhs = assign.getLastChild();
    JSDocInfo jsDoc = NodeUtil.getBestJSDocInfo(assign);

    ExportedSymbol symbolToExport =
        ExportedSymbol.fromExportAssignment(rhs, exportedNamespace, exportedSymbol, fileName);

    if (lhs.matchesQualifiedName(exportedNamespace)) {
      rhs.detach();
      Node exportSpecNode;
      if (rhs.isName() && exportsToNodes.containsKey(symbolToExport)) {
        // Rewrite the AST to export the symbol directly using information from the export
        // assignment.
        Node namedNode = exportsToNodes.get(symbolToExport);
        Node next = namedNode.getNext();
        Node parent = namedNode.getParent();
        namedNode.detach();

        Node export = new Node(Token.EXPORT, namedNode);
        export.useSourceInfoFromForTree(assign);

        nodeComments.moveComment(namedNode, export);
        parent.addChildBefore(export, next);
        exprNode.detach();

        compiler.reportChangeToEnclosingScope(parent);
        return;
      } else if (rhs.isName() && exportedSymbol.equals(rhs.getString())) {
        // Rewrite the export line to: <code>export {rhs}</code>.
        exportSpecNode = new Node(Token.EXPORT_SPECS, new Node(Token.EXPORT_SPEC, rhs));
        exportSpecNode.useSourceInfoFrom(rhs);
      } else {
        // Rewrite the export line to: <code>export const exportedSymbol = rhs</code>.
        exportSpecNode = IR.constNode(IR.name(exportedSymbol), rhs);
        exportSpecNode.useSourceInfoFrom(rhs);
      }
      exportSpecNode.setJSDocInfo(jsDoc);
      Node exportNode = new Node(Token.EXPORT, exportSpecNode);
      nodeComments.replaceWithComment(exprNode, exportNode);

    } else {
      // Assume prefix has already been exported and just trim the prefix
      nameUtil.replacePrefixInName(lhs, exportedNamespace, exportedSymbol);
    }
  }

  /** Saves the local name for imported symbols to be used for code rewriting later. */
  void registerLocalSymbol(
      String sourceFile, String fullLocalName, String requiredNamespace, String localName) {
    valueRewrite.put(sourceFile, fullLocalName, localName);
    typeRewrite.put(sourceFile, fullLocalName, localName);
    typeRewrite.put(sourceFile, requiredNamespace, localName);
  }

  /** Metadata about an exported symbol. */
  private static class ExportedSymbol {
    /** The name of the file exporting the symbol. */
    final String fileName;

    /**
     * The name that the symbol is declared in the module.
     *
     * <p>For example, {@code Foo} in: <code> class Foo {}</code>
     */
    final String localName;

    /**
     * The name that the symbol is exported under via named exports.
     *
     * <p>For example, {@code Bar} in: <code> exports {Bar}</code>.
     *
     * <p>If a symbol is directly exported, as in the case of <code>export class Foo {}</code>, the
     * {@link #localName} and {@link #exportedName} will both be {@code Foo}.
     */
    final String exportedName;

    private ExportedSymbol(String fileName, String localName, String exportedName) {
      this.fileName = checkNotNull(fileName);
      this.localName = checkNotNull(localName);
      this.exportedName = checkNotNull(exportedName);
    }

    static ExportedSymbol of(String fileName, String localName, String exportedName) {
      return new ExportedSymbol(fileName, localName, exportedName);
    }

    static ExportedSymbol fromExportAssignment(
        Node rhs, String exportedNamespace, String exportedSymbol, String fileName) {
      String localName = (rhs.getQualifiedName() != null) ? rhs.getQualifiedName() : exportedSymbol;

      String exportedName;
      if (exportedNamespace.equals(EXPORTS)) { // is a default export
        exportedName = localName;
      } else if (exportedNamespace.startsWith(EXPORTS)) { // is a named export
        exportedName =
            exportedNamespace.substring(EXPORTS.length() + 1, exportedNamespace.length());
      } else { // exported via goog.provide
        exportedName = exportedSymbol;
      }

      return new ExportedSymbol(fileName, localName, exportedName);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass())
          .add("fileName", fileName)
          .add("localName", localName)
          .add("exportedName", exportedName)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.fileName, this.localName, this.exportedName);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      ExportedSymbol that = (ExportedSymbol) obj;
      return Objects.equals(this.fileName, that.fileName)
          && Objects.equals(this.localName, that.localName)
          && Objects.equals(this.exportedName, that.exportedName);
    }
  }
}
