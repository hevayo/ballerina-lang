/*
 * Copyright (c) 2019, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ballerinalang.langserver.codeaction.providers;

import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.NonTerminalNode;
import io.ballerina.compiler.syntax.tree.ReturnTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.projects.Module;
import io.ballerina.runtime.api.constants.RuntimeConstants;
import io.ballerina.tools.text.LinePosition;
import org.ballerinalang.annotation.JavaSPIService;
import org.ballerinalang.langserver.common.CommonKeys;
import org.ballerinalang.langserver.common.constants.CommandConstants;
import org.ballerinalang.langserver.common.utils.CommonUtil;
import org.ballerinalang.langserver.commons.CodeActionContext;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Code Action for incompatible return types.
 *
 * @since 1.1.1
 */
@JavaSPIService("org.ballerinalang.langserver.commons.codeaction.spi.LSCodeActionProvider")
public class FixReturnTypeCodeAction extends AbstractCodeActionProvider {

    /**
     * {@inheritDoc}
     */
    @Override
    public List<CodeAction> getDiagBasedCodeActions(Diagnostic diagnostic, CodeActionContext context) {
        if (!(diagnostic.getMessage().toLowerCase(Locale.ROOT).contains(CommandConstants.INCOMPATIBLE_TYPES))) {
            return Collections.emptyList();
        }

        if (context.positionDetails().matchedNode().kind() != SyntaxKind.RETURN_STATEMENT) {
            return Collections.emptyList();
        }
        Matcher matcher = CommandConstants.INCOMPATIBLE_TYPE_PATTERN.matcher(diagnostic.getMessage());
        if (matcher.find() && matcher.groupCount() > 1) {
            String foundType = matcher.group(2);
            FunctionDefinitionNode funcDef = getFunctionNode(context);
            if (!RuntimeConstants.MAIN_FUNCTION_NAME.equals(funcDef.functionName().text())) {
                // Process full-qualified BType name  eg. ballerina/http:Client and if required; add
                // auto-import
                matcher = CommandConstants.FQ_TYPE_PATTERN.matcher(foundType);
                List<TextEdit> edits = new ArrayList<>();
                String editText = extractTypeName(matcher, context, foundType, edits);

                // Process function node
                Position start;
                Position end;
                if (funcDef.functionSignature().returnTypeDesc().isEmpty()) {
                    // eg. function test() {...}
                    Position funcBodyStart = CommonUtil.toPosition(funcDef.functionBody().lineRange().startLine());
                    start = funcBodyStart;
                    end = funcBodyStart;
                    editText = " returns (" + editText + ")";
                } else {
                    // eg. function test() returns () {...}
                    ReturnTypeDescriptorNode returnTypeDesc = funcDef.functionSignature().returnTypeDesc().get();
                    LinePosition retStart = returnTypeDesc.type().lineRange().startLine();
                    LinePosition retEnd = returnTypeDesc.type().lineRange().endLine();
                    start = new Position(retStart.line(),
                                         retStart.offset());
                    end = new Position(retEnd.line(), retEnd.offset());
                }
                edits.add(new TextEdit(new Range(start, end), editText));

                // Add code action
                String commandTitle = CommandConstants.CHANGE_RETURN_TYPE_TITLE + foundType + "'";
                return Collections.singletonList(createQuickFixCodeAction(commandTitle, edits, context.fileUri()));
            }
        }
        return Collections.emptyList();
    }

    private FunctionDefinitionNode getFunctionNode(CodeActionContext context) {
        NonTerminalNode parent = context.positionDetails().matchedNode();
        while (parent.kind() != SyntaxKind.FUNCTION_DEFINITION) {
            parent = parent.parent();
        }
        return (FunctionDefinitionNode) parent;
    }

    private static String extractTypeName(Matcher matcher, CodeActionContext context, String foundType,
                                          List<TextEdit> edits) {
        Optional<SyntaxTree> syntaxTree = context.workspace().syntaxTree(context.filePath());
        if (matcher.find() && matcher.groupCount() > 2 && syntaxTree.isPresent()) {
            String orgName = matcher.group(1);
            String moduleName = matcher.group(2);
            String typeName = matcher.group(3);
            String pkgId = orgName + "/" + moduleName;

            Module module = context.workspace().module(context.filePath()).orElseThrow();
            String currentOrg = module.descriptor().org().value();
            String currentModule = module.descriptor().name().packageName().value();

            if (currentOrg.equals(pkgId) && currentModule.equals(moduleName)) {
                // TODO: Check the validity of this check since currentPkgId.toString() returns version as well.
                foundType = typeName;
            } else {
                boolean pkgAlreadyImported = ((ModulePartNode) syntaxTree.get().rootNode()).imports().stream()
                        .anyMatch(importPkg -> {
                            ImportModel importModel = ImportModel.from(importPkg);
                            return importModel.orgName.equals(orgName)
                                    && importModel.moduleName.equals(moduleName);
                        });
                if (!pkgAlreadyImported) {
                    edits.addAll(CommonUtil.getAutoImportTextEdits(orgName, moduleName, context));
                }
                foundType = moduleName + CommonKeys.PKG_DELIMITER_KEYWORD + typeName;
            }
        }
        return foundType;
    }
}
