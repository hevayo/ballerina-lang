/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.ballerinalang.compiler.internal.parser;

import io.ballerinalang.compiler.internal.diagnostics.DiagnosticCode;
import io.ballerinalang.compiler.internal.diagnostics.DiagnosticErrorCode;
import io.ballerinalang.compiler.internal.parser.AbstractParserErrorHandler.Action;
import io.ballerinalang.compiler.internal.parser.AbstractParserErrorHandler.Solution;
import io.ballerinalang.compiler.internal.parser.tree.STAmbiguousCollectionNode;
import io.ballerinalang.compiler.internal.parser.tree.STAnnotAccessExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STArrayTypeDescriptorNode;
import io.ballerinalang.compiler.internal.parser.tree.STAsyncSendActionNode;
import io.ballerinalang.compiler.internal.parser.tree.STBinaryExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STBracedExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STConditionalExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STDefaultableParameterNode;
import io.ballerinalang.compiler.internal.parser.tree.STFieldAccessExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STFunctionArgumentNode;
import io.ballerinalang.compiler.internal.parser.tree.STFunctionCallExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STFunctionSignatureNode;
import io.ballerinalang.compiler.internal.parser.tree.STIndexedExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STIntersectionTypeDescriptorNode;
import io.ballerinalang.compiler.internal.parser.tree.STListConstructorExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STMappingConstructorExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STMissingToken;
import io.ballerinalang.compiler.internal.parser.tree.STNamedArgumentNode;
import io.ballerinalang.compiler.internal.parser.tree.STNilLiteralNode;
import io.ballerinalang.compiler.internal.parser.tree.STNode;
import io.ballerinalang.compiler.internal.parser.tree.STNodeDiagnostic;
import io.ballerinalang.compiler.internal.parser.tree.STNodeFactory;
import io.ballerinalang.compiler.internal.parser.tree.STNodeList;
import io.ballerinalang.compiler.internal.parser.tree.STOptionalFieldAccessExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STOptionalTypeDescriptorNode;
import io.ballerinalang.compiler.internal.parser.tree.STPositionalArgumentNode;
import io.ballerinalang.compiler.internal.parser.tree.STQualifiedNameReferenceNode;
import io.ballerinalang.compiler.internal.parser.tree.STRemoteMethodCallActionNode;
import io.ballerinalang.compiler.internal.parser.tree.STRequiredParameterNode;
import io.ballerinalang.compiler.internal.parser.tree.STRestArgumentNode;
import io.ballerinalang.compiler.internal.parser.tree.STRestBindingPatternNode;
import io.ballerinalang.compiler.internal.parser.tree.STRestParameterNode;
import io.ballerinalang.compiler.internal.parser.tree.STSimpleNameReferenceNode;
import io.ballerinalang.compiler.internal.parser.tree.STSpecificFieldNode;
import io.ballerinalang.compiler.internal.parser.tree.STSyncSendActionNode;
import io.ballerinalang.compiler.internal.parser.tree.STToken;
import io.ballerinalang.compiler.internal.parser.tree.STTypeReferenceTypeDescNode;
import io.ballerinalang.compiler.internal.parser.tree.STTypeTestExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STTypedBindingPatternNode;
import io.ballerinalang.compiler.internal.parser.tree.STUnaryExpressionNode;
import io.ballerinalang.compiler.internal.parser.tree.STUnionTypeDescriptorNode;
import io.ballerinalang.compiler.internal.syntax.SyntaxUtils;
import io.ballerinalang.compiler.syntax.tree.SyntaxKind;
import io.ballerinalang.compiler.text.TextDocument;
import io.ballerinalang.compiler.text.TextDocuments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A LL(k) recursive-descent parser for ballerina.
 *
 * @since 1.2.0
 */
public class BallerinaParser extends AbstractParser {

    private static final OperatorPrecedence DEFAULT_OP_PRECEDENCE = OperatorPrecedence.DEFAULT;

    protected BallerinaParser(AbstractTokenReader tokenReader) {
        super(tokenReader, new BallerinaParserErrorHandler(tokenReader));
    }

    /**
     * Start parsing the given input.
     *
     * @return Parsed node
     */
    @Override
    public STNode parse() {
        return parseCompUnit();
    }

    /**
     * Start parsing the input from a given context. Supported starting points are:
     * <ul>
     * <li>Module part (a file)</li>
     * <li>Top level node</li>
     * <li>Statement</li>
     * <li>Expression</li>
     * </ul>
     *
     * @param context Context to start parsing
     * @return Parsed node
     */
    public STNode parse(ParserRuleContext context) {
        switch (context) {
            case COMP_UNIT:
                return parseCompUnit();
            case TOP_LEVEL_NODE:
                startContext(ParserRuleContext.COMP_UNIT);
                return parseTopLevelNode();
            case STATEMENT:
                startContext(ParserRuleContext.COMP_UNIT);
                // startContext(ParserRuleContext.FUNC_DEF_OR_FUNC_TYPE);
                startContext(ParserRuleContext.FUNC_BODY_BLOCK);
                return parseStatement();
            case EXPRESSION:
                startContext(ParserRuleContext.COMP_UNIT);
                // startContext(ParserRuleContext.FUNC_DEF_OR_FUNC_TYPE);
                startContext(ParserRuleContext.FUNC_BODY_BLOCK);
                startContext(ParserRuleContext.STATEMENT);
                return parseExpression();
            default:
                throw new UnsupportedOperationException("Cannot start parsing from: " + context);
        }
    }

    /**
     * Resume the parsing from the given context.
     *
     * @param context Context to resume parsing
     * @param args Arguments that requires to continue parsing from the given parser context
     * @return Parsed node
     */
    @Override
    public STNode resumeParsing(ParserRuleContext context, Object... args) {
        // TODO: revisit the commented resume-points
        switch (context) {
            case FUNC_BODY:
                return parseFunctionBody((boolean) args[0]);
            case OPEN_BRACE:
                return parseOpenBrace();
            case CLOSE_BRACE:
                return parseCloseBrace();
            case FUNC_NAME:
                return parseFunctionName();
            case OPEN_PARENTHESIS:
            case ARG_LIST_START:
                return parseOpenParenthesis(context);
            case SIMPLE_TYPE_DESCRIPTOR:
                return parseSimpleTypeDescriptor();
            case ASSIGN_OP:
                return parseAssignOp();
            case EXTERNAL_KEYWORD:
                return parseExternalKeyword();
            case SEMICOLON:
                return parseSemicolon();
            case CLOSE_PARENTHESIS:
                return parseCloseParenthesis();
            case VARIABLE_NAME:
                return parseVariableName();
            case TERMINAL_EXPRESSION:
                return parseTerminalExpression((STNode) args[0], (boolean) args[1], (boolean) args[2],
                        (boolean) args[3]);
            case STATEMENT:
                return parseStatement();
            case STATEMENT_WITHOUT_ANNOTS:
                return parseStatement((STNode) args[0]);
            case EXPRESSION_RHS:
                return parseExpressionRhs((OperatorPrecedence) args[0], (STNode) args[1], (boolean) args[2],
                        (boolean) args[3], (boolean) args[4], (boolean) args[5]);
            case PARAMETER_START:
                return parseParameter((SyntaxKind) args[0], (STNode) args[1], (int) args[2], (boolean) args[3]);
            case PARAMETER_WITHOUT_ANNOTS:
                return parseParamGivenAnnots((SyntaxKind) args[0], (STNode) args[1], (STNode) args[2], (int) args[3],
                        (boolean) args[4]);
            case AFTER_PARAMETER_TYPE:
                return parseAfterParamType((SyntaxKind) args[0], (STNode) args[1], (STNode) args[2], (STNode) args[3],
                        (STNode) args[4], (boolean) args[5]);
            case PARAMETER_NAME_RHS:
                return parseParameterRhs((SyntaxKind) args[0], (STNode) args[1], (STNode) args[2], (STNode) args[3],
                        (STNode) args[4], (STNode) args[5]);
            case TOP_LEVEL_NODE:
                return parseTopLevelNode();
            case TOP_LEVEL_NODE_WITHOUT_METADATA:
                return parseTopLevelNode((STNode) args[0]);
            case TOP_LEVEL_NODE_WITHOUT_MODIFIER:
                return parseTopLevelNode((STNode) args[0], (STNode) args[1]);
            case TYPE_NAME_OR_VAR_NAME:
            case RECORD_FIELD_NAME_OR_TYPE_NAME:
            case TYPE_REFERENCE:
            case ANNOT_REFERENCE:
            case FIELD_ACCESS_IDENTIFIER:
                return parseQualifiedIdentifier(context, (boolean) args[0]);
            case VAR_DECL_STMT_RHS:
                return parseVarDeclRhs((STNode) args[0], (STNode) args[1], (STNode) args[2], (boolean) args[3]);
            case FIELD_DESCRIPTOR_RHS:
                return parseFieldDescriptorRhs((STNode) args[0], (STNode) args[1], (STNode) args[2], (STNode) args[3]);
            case RECORD_BODY_START:
                return parseRecordBodyStartDelimiter();
            case TYPE_DESCRIPTOR:
                return parseTypeDescriptorInternal((ParserRuleContext) args[0], (boolean) args[1]);
            case OBJECT_MEMBER_START:
                return parseObjectMember();
            case OBJECT_FUNC_OR_FIELD_WITHOUT_VISIBILITY:
                return parseObjectMethodOrField((STNode) args[0], (STNode) args[1]);
            case OBJECT_FIELD_RHS:
                return parseObjectFieldRhs((STNode) args[0], (STNode) args[1], (STNode) args[2], (STNode) args[3],
                        (STNode) args[4]);
            case OBJECT_TYPE_QUALIFIER:
                return parseObjectTypeQualifiers();
            case OBJECT_KEYWORD:
                return parseObjectKeyword();
            case TYPE_NAME:
                return parseTypeName();
            case IF_KEYWORD:
                return parseIfKeyword();
            case ELSE_KEYWORD:
                return parseElseKeyword();
            case ELSE_BODY:
                return parseElseBody();
            case WHILE_KEYWORD:
                return parseWhileKeyword();
            case PANIC_KEYWORD:
                return parsePanicKeyword();
            case IMPORT_DECL_RHS:
                return parseImportDecl((STNode) args[0], (STNode) args[1]);
            case IMPORT_PREFIX:
                return parseImportPrefix();
            case IMPORT_MODULE_NAME:
            case IMPORT_ORG_OR_MODULE_NAME:
            case VARIABLE_REF:// 2 resume-points : parseQualifiedIdentifier(context)
            case SERVICE_NAME:
            case IMPLICIT_ANON_FUNC_PARAM:
            case MAPPING_FIELD_NAME:
            case RECEIVE_FIELD_NAME:
            case MODULE_ENUM_NAME:
            case ENUM_MEMBER_NAME:
                return parseIdentifier(context);
            case IMPORT_KEYWORD:
                return parseImportKeyword();
            case SLASH:
                return parseSlashToken();
            case DOT:
                return parseDotToken();
            case IMPORT_VERSION_DECL:
                return parseVersion();
            case VERSION_KEYWORD:
                return parseVersionKeyword();
            case VERSION_NUMBER:
                return parseVersionNumber();
            case DECIMAL_INTEGER_LITERAL:
            case MAJOR_VERSION:
            case MINOR_VERSION:
            case PATCH_VERSION:
                return parseDecimalIntLiteral(context);
            case IMPORT_SUB_VERSION:
                return parseSubVersion(context);
            case IMPORT_PREFIX_DECL:
                return parseImportPrefixDecl();
            case AS_KEYWORD:
                return parseAsKeyword();
            case CONTINUE_KEYWORD:
                return parseContinueKeyword();
            case BREAK_KEYWORD:
                return parseBreakKeyword();
            case RETURN_KEYWORD:
                return parseReturnKeyword();
            case MAPPING_FIELD:
            case FIRST_MAPPING_FIELD:
                return parseMappingField((ParserRuleContext) args[0]);
            case SPECIFIC_FIELD_RHS:// 2 resume-points : parseMappingFieldRhs(key)
                return parseSpecificFieldRhs((STNode) args[0], (STNode) args[1]);
            case STRING_LITERAL:
                return parseStringLiteral();
            case COLON:
                return parseColon();
            case OPEN_BRACKET:
                return parseOpenBracket();
            case RESOURCE_DEF:
                return parseResource();
            case OPTIONAL_SERVICE_NAME:
                return parseServiceName();
            case SERVICE_KEYWORD:
                return parseServiceKeyword();
            case ON_KEYWORD:
                return parseOnKeyword();
            case RESOURCE_KEYWORD:
                return parseResourceKeyword();
            case LISTENER_KEYWORD:
                return parseListenerKeyword();
            case NIL_TYPE_DESCRIPTOR:// following method is only referred in resume parsing
                return parseNilTypeDescriptor();
            case COMPOUND_ASSIGNMENT_STMT:// following method is only referred in resume parsing
                return parseCompoundAssignmentStmt();
            case TYPEOF_KEYWORD:
                return parseTypeofKeyword();
            case ARRAY_LENGTH:
                return parseArrayLength();
            case IS_KEYWORD:
                return parseIsKeyword();
            case STMT_START_WITH_EXPR_RHS:
                return parseStatementStartWithExprRhs((STNode) args[0]);
            case COMMA:
                return parseComma();
            case CONST_DECL_TYPE:
                return parseConstDecl((STNode) args[0], (STNode) args[1], (STNode) args[2]);
            case BINDING_PATTERN_OR_EXPR_RHS:// 2 resume-points : parseTypeDescOrExprRhs()
                return parseTypedBindingPatternOrExprRhs((STNode) args[0], (boolean) args[1]);
            case LT:
                return parseLTToken();
            case GT:
                return parseGTToken();
            case RECORD_FIELD_OR_RECORD_END:
                return parseFieldOrRestDescriptor((boolean) args[0]);
            case ANNOTATION_KEYWORD:
                return parseAnnotationKeyword();
            case ANNOT_DECL_OPTIONAL_TYPE:
                return parseAnnotationDeclFromType((STNode) args[0], (STNode) args[1], (STNode) args[2],
                        (STNode) args[3]);
            case ANNOT_DECL_RHS:
                return parseAnnotationDeclRhs((STNode) args[0], (STNode) args[1], (STNode) args[2], (STNode) args[3],
                        (STNode) args[4]);
            case ANNOT_OPTIONAL_ATTACH_POINTS:
                return parseAnnotationDeclAttachPoints((STNode) args[0], (STNode) args[1], (STNode) args[2],
                        (STNode) args[3], (STNode) args[4], (STNode) args[5]);
            case SOURCE_KEYWORD:
                return parseSourceKeyword();
            case ATTACH_POINT_IDENT:
                return parseAttachPointIdent((STNode) args[0]);
            case IDENT_AFTER_OBJECT_IDENT:
                return parseIdentAfterObjectIdent();
            case FUNCTION_IDENT:
                return parseFunctionIdent();
            case FIELD_IDENT:
                return parseFieldIdent();
            case ATTACH_POINT_END:
                return parseAttachPointEnd();
            case XMLNS_KEYWORD:
                return parseXMLNSKeyword();
            case XML_NAMESPACE_PREFIX_DECL:
                return parseXMLDeclRhs((STNode) args[0], (STNode) args[1], (boolean) args[2]);
            case NAMESPACE_PREFIX:
                return parseNamespacePrefix();
            case WORKER_KEYWORD:
                return parseWorkerKeyword();
            case WORKER_NAME:
                return parseWorkerName();
            case FORK_KEYWORD:
                return parseForkKeyword();
            case TRAP_KEYWORD:
                return parseTrapKeyword();
            case IN_KEYWORD:
                return parseInKeyword();
            case FOREACH_KEYWORD:
                return parseForEachKeyword();
            case TABLE_KEYWORD:
                return parseTableKeyword();
            case KEY_KEYWORD:
                return parseKeyKeyword();
            case TABLE_KEYWORD_RHS:
                return parseTableConstructorOrQuery((STNode) args[0], (boolean) args[1]);
            case ERROR_KEYWORD:
                return parseErrorKeyword();
            case LET_KEYWORD:
                return parseLetKeyword();
            case STREAM_KEYWORD:
                return parseStreamKeyword();
            case STREAM_TYPE_FIRST_PARAM_RHS:
                return parseStreamTypeParamsNode((STNode) args[0], (STNode) args[1]);
            case TEMPLATE_START:
            case TEMPLATE_END:
                return parseBacktickToken(context);
            case KEY_CONSTRAINTS_RHS:
                return parseKeyConstraint((STNode) args[0]);
            case FUNCTION_KEYWORD_RHS:
                return parseFunctionKeywordRhs((STNode) args[0], (STNode) args[1], (boolean) args[2],
                        (List<STNode>) args[3]);
            case RETURNS_KEYWORD:
                return parseReturnsKeyword();
            case NEW_KEYWORD:
                return parseNewKeyword();
            case FROM_KEYWORD:
                return parseFromKeyword();
            case WHERE_KEYWORD:
                return parseWhereKeyword();
            case SELECT_KEYWORD:
                return parseSelectKeyword();
            case ORDER_KEYWORD:
                return parseOrderKeyword();
            case BY_KEYWORD:
                return parseByKeyword();
            case ASCENDING_KEYWORD:
                return parseAscendingKeyword();
            case DESCENDING_KEYWORD:
                return parseDescendingKeyword();
            case ORDER_KEY_LIST_END:
                return parseOrderKeyListMemberEnd();
            case TABLE_CONSTRUCTOR_OR_QUERY_START:
                return parseTableConstructorOrQuery((boolean) args[0]);
            case TABLE_CONSTRUCTOR_OR_QUERY_RHS:
                return parseTableConstructorOrQueryRhs((STNode) args[0], (STNode) args[1], (boolean) args[2]);
            case QUERY_PIPELINE_RHS:
                return parseIntermediateClause((boolean) args[0]);
            case ANON_FUNC_BODY:
                return parseAnonFuncBody((boolean) args[0]);
            case CLOSE_BRACKET:
                return parseCloseBracket();
            case ARG_START:
                return parseArgument();
            case ARG_END:
                return parseArgEnd();
            case MAPPING_FIELD_END:
                return parseMappingFieldEnd();
            case FUNCTION_KEYWORD:
                return parseFunctionKeyword();
            case FIELD_OR_REST_DESCIPTOR_RHS:
                return parseFieldOrRestDescriptorRhs((STNode) args[0], (STNode) args[1]);
            case TYPE_DESC_IN_TUPLE_RHS:
                return parseTupleMemberRhs();
            case LIST_BINDING_PATTERN_MEMBER_END:
                return parseListBindingPatternMemberRhs();
            case MAPPING_BINDING_PATTERN_END:
                return parseMappingBindingPatternEnd();
            case FIELD_BINDING_PATTERN_NAME:
                return parseFieldBindingPattern();
            case CONSTANT_EXPRESSION_START:
                return parseSimpleConstExprInternal();
            case LIST_CONSTRUCTOR_MEMBER_END:
                return parseListConstructorMemberEnd();
            case NIL_OR_PARENTHESISED_TYPE_DESC_RHS:
                return parseNilOrParenthesisedTypeDescRhs((STNode) args[0]);
            case ANON_FUNC_PARAM_RHS:
                return parseImplicitAnonFuncParamEnd();
            case LIST_BINDING_PATTERN:// revisit
                return parseListBindingPattern();
            case BINDING_PATTERN:
                return parseBindingPattern();
            case PEER_WORKER_NAME:
                return parsePeerWorkerName();
            case SYNC_SEND_TOKEN:
                return parseSyncSendToken();
            case LEFT_ARROW_TOKEN:
                return parseLeftArrowToken();
            case RECEIVE_WORKERS:
                return parseReceiveWorkers();
            case WAIT_KEYWORD:
                return parseWaitKeyword();
            case WAIT_FUTURE_EXPR_END:
                return parseWaitFutureExprEnd((int) args[0]);
            case WAIT_FIELD_NAME:// revisit
                return parseWaitField();
            case WAIT_FIELD_END:
                return parseWaitFieldEnd();
            case ANNOT_CHAINING_TOKEN:
                return parseAnnotChainingToken();
            case DO_KEYWORD:
                return parseDoKeyword();
            case MEMBER_ACCESS_KEY_EXPR_END:
                return parseMemberAccessKeyExprEnd();
            case OPTIONAL_CHAINING_TOKEN:
                return parseOptionalChainingToken();
            case RETRY_KEYWORD_RHS:
                return parseRetryKeywordRhs((STNode) args[0]);
            case RETRY_TYPE_PARAM_RHS:
                return parseRetryTypeParamRhs((STNode) args[0], (STNode) args[1]);
            case TRANSACTION_KEYWORD:
                return parseTransactionKeyword();
            case COMMIT_KEYWORD:
                return parseCommitKeyword();
            case RETRY_KEYWORD:
                return parseRetryKeyword();
            case ROLLBACK_KEYWORD:// 2 resume-points : parseTransactionalKeyword()
                return parseRollbackKeyword();
            case RETRY_BODY:
                return parseRetryBody();
            case ENUM_MEMBER_END:
                return parseEnumMemberEnd();
            case BRACKETED_LIST_MEMBER_END:
                return parseBracketedListMemberEnd();
            case STMT_START_BRACKETED_LIST_MEMBER:
                return parseStatementStartBracketedListMember();
            case TYPED_BINDING_PATTERN_TYPE_RHS:
                return parseTypedBindingPatternTypeRhs((STNode) args[0], (ParserRuleContext) args[1],
                        (boolean) args[2]);
            case BRACKETED_LIST_RHS:
                return parseTypedBindingPatternOrMemberAccessRhs((STNode) args[0], (STNode) args[1], (STNode) args[2],
                        (STNode) args[3], (boolean) args[4], (boolean) args[5], (ParserRuleContext) args[6]);
            case UNION_OR_INTERSECTION_TOKEN:
                return parseUnionOrIntersectionToken();
            case BRACKETED_LIST_MEMBER:
            case LIST_BINDING_MEMBER_OR_ARRAY_LENGTH:
                return parseBracketedListMember((boolean) args[0]);
            case BASE16_KEYWORD:
                return parseBase16Keyword();
            case BASE64_KEYWORD:
                return parseBase64Keyword();
            case DOT_LT_TOKEN:
                return parseDotLTToken();
            case SLASH_LT_TOKEN:
                return parseSlashLTToken();
            case DOUBLE_SLASH_DOUBLE_ASTERISK_LT_TOKEN:
                return parseDoubleSlashDoubleAsteriskLTToken();
            case XML_ATOMIC_NAME_PATTERN_START:
                return parseXMLAtomicNamePatternBody();
            case BRACED_EXPR_OR_ANON_FUNC_PARAM_RHS:
                return parseBracedExprOrAnonFuncParamRhs((STNode) args[0], (STNode) args[1], (boolean) args[2]);
            case READONLY_KEYWORD:
                return parseReadonlyKeyword();
            case SPECIFIC_FIELD:
                return parseSpecificField((STNode) args[0]);
            case OPTIONAL_MATCH_GUARD:
                return parseMatchGuard();
            case MATCH_PATTERN_START:
                return parseMatchPattern();
            case MATCH_PATTERN_RHS:
                return parseMatchPatternEnd();
            case ENUM_MEMBER_RHS:
                return parseEnumMemberRhs((STNode) args[0], (STNode) args[1]);
            case RECEIVE_FIELD:
                return parseReceiveField();
            case PUBLIC_KEYWORD:// 2 resume-points : parseObjectMemberVisibility()
                return parseQualifier();
            case PARAM_END:
                return parseParameterRhs();
            case ELLIPSIS:
                return parseEllipsis();
            case BINARY_OPERATOR:
                return parseBinaryOperator();
            case TYPE_KEYWORD:
                return parseTypeKeyword();
            case CLOSED_RECORD_BODY_START:
                return parseClosedRecordBodyStart();
            case CLOSED_RECORD_BODY_END:
                return parseClosedRecordBodyEnd();
            case QUESTION_MARK:
                return parseQuestionMark();
            case FINAL_KEYWORD:
                return parseFinalKeyword();
            case CLIENT_KEYWORD:
                return parseClientKeyword();
            case ABSTRACT_KEYWORD:
                return parseAbstractKeyword();
            case REMOTE_KEYWORD:
                return parseRemoteKeyword();
            case FAIL_KEYWORD:
                return parseFailKeyword();
            case CHECKING_KEYWORD:
                return parseCheckingKeyword();
            case COMPOUND_BINARY_OPERATOR:
                return parseCompoundBinaryOperator();
            case CONST_DECL_RHS:
                return parseConstantOrListenerDeclRhs((STNode) args[0], (STNode) args[1], (STNode) args[2],
                        (STNode) args[3], (boolean) args[4]);
            case CONST_KEYWORD:
                return parseConstantKeyword();
            case UNARY_OPERATOR:
                return parseUnaryOperator();
            case AT:
                return parseAtToken();
            case REMOTE_CALL_OR_ASYNC_SEND_RHS:
                return parseRemoteCallOrAsyncSendActionRhs((STNode) args[0], (boolean) args[1], (STNode) args[2]);
            case DEFAULT_KEYWORD:
                return parseDefaultKeyword();
            case RIGHT_ARROW:
                return parseRightArrow();
            case PARAMETERIZED_TYPE:
                return parseParameterizedTypeKeyword();
            case ANNOTATION_TAG:
                return parseAnnotationTag();
            case ATTACH_POINT:
                return parseAnnotationAttachPoint();
            case LOCK_KEYWORD:
                return parseLockKeyword();
            case PIPE:
                return parsePipeToken();
            case STRING_KEYWORD:
                return parseStringKeyword();
            case XML_KEYWORD:
                return parseXMLKeyword();
            case INTERPOLATION_START_TOKEN:
                return parseInterpolationStart();
            case EXPR_FUNC_BODY_START:
                return parseDoubleRightArrow();
            case START_KEYWORD:
                return parseStartKeyword();
            case FLUSH_KEYWORD:
                return parseFlushKeyword();
            case ENUM_KEYWORD:
                return parseEnumKeyword();
            case MATCH_KEYWORD:
                return parseMatchKeyword();
            case RECORD_KEYWORD:
                return parseRecordKeyword();
            case LIST_MATCH_PATTERN_MEMBER_RHS:
                return parseListMatchPatternMemberRhs();
            case LIST_BINDING_PATTERN_MEMBER:
                return parseListBindingPatternMember();
            case FIELD_MATCH_PATTERN_MEMBER:
                return parseFieldMatchPatternMember();
            case FIELD_MATCH_PATTERN_MEMBER_RHS:
                return parseFieldMatchPatternRhs();
            case FUNC_MATCH_PATTERN_OR_CONST_PATTERN:
                return parseFunctionalMatchPatternOrConsPattern((STNode) args[0]);
            case ARG_MATCH_PATTERN:
                return parseArgMatchPattern();
            case ARG_MATCH_PATTERN_RHS:
                return parseArgMatchPatternRhs();
            case ARG_BINDING_PATTERN:
                return parseArgBindingPattern();
            case EXTERNAL_FUNC_BODY_OPTIONAL_ANNOTS:
                return parseExternalFuncBodyRhs((STNode) args[0]);
            case ARG_BINDING_PATTERN_END:
                return parseArgsBindingPatternEnd();
            case TABLE_ROW_END:
                return parseTableRowEnd();
            case LIST_BP_OR_LIST_CONSTRUCTOR_MEMBER:
                return parseListBindingPatternOrListConstructorMember();
            case TUPLE_TYPE_DESC_OR_LIST_CONST_MEMBER:
                return parseTupleTypeDescOrListConstructorMember((STNode) args[0]);
            case OBJECT_METHOD_START_WITHOUT_REMOTE:
            case TOP_LEVEL_FUNC_DEF_OR_FUNC_TYPE_DESC:
                return parseFuncDefOrFuncTypeDesc((ParserRuleContext) args[0], (STNode) args[1], (boolean) args[2],
                        (List<STNode>) args[3]);
            // case RECORD_BODY_END:
            // case OBJECT_MEMBER_WITHOUT_METADATA:
            // case REMOTE_CALL_OR_ASYNC_SEND_END:
            // case RECEIVE_FIELD_END:
            // case MAPPING_BP_OR_MAPPING_CONSTRUCTOR_MEMBER:
            default:
                throw new IllegalStateException("cannot resume parsing the rule: " + context);
        }
    }

    /*
     * Private methods.
     */

    /**
     * Parse a given input and returns the AST. Starts parsing from the top of a compilation unit.
     *
     * @return Parsed node
     */
    private STNode parseCompUnit() {
        startContext(ParserRuleContext.COMP_UNIT);
        STToken token = peek();
        List<STNode> otherDecls = new ArrayList<>();
        List<STNode> importDecls = new ArrayList<>();

        boolean processImports = true;
        while (token.kind != SyntaxKind.EOF_TOKEN) {
            STNode decl = parseTopLevelNode(token.kind);
            if (decl == null) {
                break;
            }
            if (decl.kind == SyntaxKind.IMPORT_DECLARATION) {
                if (processImports) {
                    importDecls.add(decl);
                } else {
                    // If an import occurs after any other module level declaration,
                    // we add it to the other-decl list to preserve the order. But
                    // log an error and mark it as invalid.
                    updateLastNodeInListWithInvalidNode(otherDecls, decl,
                            DiagnosticErrorCode.ERROR_IMPORT_DECLARATION_AFTER_OTHER_DECLARATIONS);
                }
            } else {
                if (processImports) {
                    // While processing imports, if we reach any other declaration,
                    // then mark this as the end of processing imports.
                    processImports = false;
                }
                otherDecls.add(decl);
            }
            token = peek();
        }

        STToken eof = consume();
        endContext();

        return STNodeFactory.createModulePartNode(STNodeFactory.createNodeList(importDecls),
                STNodeFactory.createNodeList(otherDecls), eof);
    }

    /**
     * Parse top level node having an optional modifier preceding it.
     *
     * @return Parsed node
     */
    private STNode parseTopLevelNode() {
        STToken token = peek();
        return parseTopLevelNode(token.kind);
    }

    protected STNode parseTopLevelNode(SyntaxKind tokenKind) {
        STNode metadata;
        switch (tokenKind) {
            case EOF_TOKEN:
                return null;
            case DOCUMENTATION_STRING:
            case AT_TOKEN:
                metadata = parseMetaData(tokenKind);
                return parseTopLevelNode(metadata);
            case IMPORT_KEYWORD:
            case FINAL_KEYWORD:
            case PUBLIC_KEYWORD:
            case FUNCTION_KEYWORD:
            case TYPE_KEYWORD:
            case LISTENER_KEYWORD:
            case CONST_KEYWORD:
            case ANNOTATION_KEYWORD:
            case XMLNS_KEYWORD:
            case SERVICE_KEYWORD:
            case ENUM_KEYWORD:
            case TRANSACTIONAL_KEYWORD:
                metadata = createEmptyMetadata();
                break;
            case IDENTIFIER_TOKEN:
                // Here we assume that after recovering, we'll never reach here.
                // Otherwise the tokenOffset will not be 1.
                if (isModuleVarDeclStart(1)) {
                    // This is an early exit, so that we don't have to do the same check again.
                    return parseModuleVarDecl(createEmptyMetadata(), null);
                }
                // Else fall through
            default:
                if (isTypeStartingToken(tokenKind) && tokenKind != SyntaxKind.IDENTIFIER_TOKEN) {
                    metadata = createEmptyMetadata();
                    break;
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.TOP_LEVEL_NODE);

                if (solution.action == Action.KEEP) {
                    // If the solution is {@link Action#KEEP}, that means next immediate token is
                    // at the correct place, but some token after that is not. There only one such
                    // cases here, which is the `case IDENTIFIER_TOKEN`. So accept it, and continue.
                    metadata = STNodeFactory.createEmptyNodeList();
                    break;
                }

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTopLevelNode(solution.tokenKind);
        }

        return parseTopLevelNode(tokenKind, metadata);
    }

    /**
     * Parse top level node having an optional modifier preceding it, given the next token kind.
     *
     * @param metadata Next token kind
     * @return Parsed node
     */
    private STNode parseTopLevelNode(STNode metadata) {
        STToken nextToken = peek();
        return parseTopLevelNode(nextToken.kind, metadata);
    }

    private STNode parseTopLevelNode(SyntaxKind tokenKind, STNode metadata) {
        STNode qualifier = null;
        switch (tokenKind) {
            case EOF_TOKEN:
                if (metadata != null) {
                    addInvalidNodeToNextToken(metadata, DiagnosticErrorCode.ERROR_INVALID_METADATA);
                }
                return null;
            case PUBLIC_KEYWORD:
                qualifier = parseQualifier();
                tokenKind = peek().kind;
                break;
            case FUNCTION_KEYWORD:
            case TYPE_KEYWORD:
            case LISTENER_KEYWORD:
            case CONST_KEYWORD:
            case FINAL_KEYWORD:
            case IMPORT_KEYWORD:
            case ANNOTATION_KEYWORD:
            case XMLNS_KEYWORD:
            case ENUM_KEYWORD:
            case TRANSACTIONAL_KEYWORD:
                break;
            case IDENTIFIER_TOKEN:
                // Here we assume that after recovering, we'll never reach here.
                // Otherwise the tokenOffset will not be 1.
                if (isModuleVarDeclStart(1)) {
                    // This is an early exit, so that we don't have to do the same check again.
                    return parseModuleVarDecl(metadata, null);
                }
                // Else fall through
            default:
                if (isTypeStartingToken(tokenKind) && tokenKind != SyntaxKind.IDENTIFIER_TOKEN) {
                    break;
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.TOP_LEVEL_NODE_WITHOUT_METADATA, metadata);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                if (solution.action == Action.KEEP) {
                    // If the solution is {@link Action#KEEP}, that means next immediate token is
                    // at the correct place, but some token after that is not. There only one such
                    // cases here, which is the `case IDENTIFIER_TOKEN`. So accept it, and continue.
                    qualifier = STNodeFactory.createEmptyNode();
                    break;
                }

                return parseTopLevelNode(solution.tokenKind, metadata);
        }

        return parseTopLevelNode(tokenKind, metadata, qualifier);
    }

    /**
     * Check whether the cursor is at the start of a module level var-decl.
     *
     * @param lookahead Offset of the token to to check
     * @return <code>true</code> if the cursor is at the start of a module level var-decl.
     *         <code>false</code> otherwise.
     */
    private boolean isModuleVarDeclStart(int lookahead) {
        // Assumes that we reach here after a peek()
        STToken nextToken = peek(lookahead + 1);
        switch (nextToken.kind) {
            case EQUAL_TOKEN: // Scenario: foo = . Even though this is not valid, consider this as a var-decl and
                // continue;
            case OPEN_BRACKET_TOKEN: // Scenario foo[] (Array type descriptor with custom type)
            case QUESTION_MARK_TOKEN: // Scenario foo? (Optional type descriptor with custom type)
            case PIPE_TOKEN: // Scenario foo | (Union type descriptor with custom type)
            case BITWISE_AND_TOKEN: // Scenario foo & (Intersection type descriptor with custom type)
            case OPEN_BRACE_TOKEN: // Scenario foo[] (Array type descriptor with custom type)
            case ERROR_KEYWORD: // Scenario foo error (error-binding-pattern)
            case EOF_TOKEN:
                return true;
            case IDENTIFIER_TOKEN:
                switch (peek(lookahead + 2).kind) {
                    case EQUAL_TOKEN: // Scenario: foo bar =
                    case SEMICOLON_TOKEN: // Scenario: foo bar;
                    case EOF_TOKEN:
                        return true;
                    default:
                        return false;
                }
            case COLON_TOKEN:
                if (lookahead > 1) {
                    // This means there's a colon somewhere after the type name.
                    // This is not a valid var-decl.
                    return false;
                }

                switch (peek(lookahead + 2).kind) {
                    case IDENTIFIER_TOKEN: // Scenario: foo:bar baz ...
                        return isModuleVarDeclStart(lookahead + 2);
                    case EOF_TOKEN: // Scenario: foo: recovery
                        return true;
                    default:
                        return false;
                }
            default:
                return false;
        }
    }

    /**
     * Parse import declaration.
     * <p>
     * <code>import-decl :=  import [org-name /] module-name [version sem-ver] [as import-prefix] ;</code>
     *
     * @return Parsed node
     */
    private STNode parseImportDecl() {
        startContext(ParserRuleContext.IMPORT_DECL);
        this.tokenReader.startMode(ParserMode.IMPORT);
        STNode importKeyword = parseImportKeyword();
        STNode identifier = parseIdentifier(ParserRuleContext.IMPORT_ORG_OR_MODULE_NAME);

        STToken token = peek();
        STNode importDecl = parseImportDecl(token.kind, importKeyword, identifier);
        this.tokenReader.endMode();
        endContext();
        return importDecl;
    }

    /**
     * Parse import keyword.
     *
     * @return Parsed node
     */
    private STNode parseImportKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.IMPORT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.IMPORT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse identifier.
     *
     * @return Parsed node
     */
    private STNode parseIdentifier(ParserRuleContext currentCtx) {
        STToken token = peek();
        if (token.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            return consume();
        } else if (token.kind == SyntaxKind.MAP_KEYWORD) {
            STToken mapKeyword = consume();
            return STNodeFactory.createIdentifierToken(mapKeyword.text(), mapKeyword.leadingMinutiae(),
                    mapKeyword.trailingMinutiae(), mapKeyword.diagnostics());
        } else {
            Solution sol = recover(token, currentCtx);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse RHS of the import declaration. This includes the components after the
     * starting identifier (org-name/module-name) of the import decl.
     *
     * @param importKeyword Import keyword
     * @param identifier Org-name or the module name
     * @return Parsed node
     */
    private STNode parseImportDecl(STNode importKeyword, STNode identifier) {
        STToken nextToken = peek();
        return parseImportDecl(nextToken.kind, importKeyword, identifier);
    }

    private STNode parseImportDecl(SyntaxKind tokenKind, STNode importKeyword, STNode identifier) {
        STNode orgName;
        STNode moduleName;
        STNode version;
        STNode alias;

        switch (tokenKind) {
            case SLASH_TOKEN:
                STNode slash = parseSlashToken();
                orgName = STNodeFactory.createImportOrgNameNode(identifier, slash);
                moduleName = parseModuleName();
                version = parseVersion();
                alias = parseImportPrefixDecl();
                break;
            case DOT_TOKEN:
            case VERSION_KEYWORD:
                orgName = STNodeFactory.createEmptyNode();
                moduleName = parseModuleName(tokenKind, identifier);
                version = parseVersion();
                alias = parseImportPrefixDecl();
                break;
            case AS_KEYWORD:
                orgName = STNodeFactory.createEmptyNode();
                moduleName = parseModuleName(tokenKind, identifier);
                version = STNodeFactory.createEmptyNode();
                alias = parseImportPrefixDecl();
                break;
            case SEMICOLON_TOKEN:
                orgName = STNodeFactory.createEmptyNode();
                moduleName = parseModuleName(tokenKind, identifier);
                version = STNodeFactory.createEmptyNode();
                alias = STNodeFactory.createEmptyNode();
                break;
            default:
                Solution solution = recover(peek(), ParserRuleContext.IMPORT_DECL_RHS, importKeyword, identifier);

                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseImportDecl(solution.tokenKind, importKeyword, identifier);
        }

        STNode semicolon = parseSemicolon();
        return STNodeFactory.createImportDeclarationNode(importKeyword, orgName, moduleName, version, alias, semicolon);
    }

    /**
     * parse slash token.
     *
     * @return Parsed node
     */
    private STNode parseSlashToken() {
        STToken token = peek();
        if (token.kind == SyntaxKind.SLASH_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.SLASH);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse dot token.
     *
     * @return Parsed node
     */
    private STNode parseDotToken() {
        STToken token = peek();
        if (token.kind == SyntaxKind.DOT_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(peek(), ParserRuleContext.DOT);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse module name of a import declaration.
     *
     * @return Parsed node
     */
    private STNode parseModuleName() {
        STNode moduleNameStart = parseIdentifier(ParserRuleContext.IMPORT_MODULE_NAME);
        return parseModuleName(peek().kind, moduleNameStart);
    }

    /**
     * Parse import module name of a import declaration, given the module name start identifier.
     *
     * @param moduleNameStart Starting identifier of the module name
     * @return Parsed node
     */
    private STNode parseModuleName(SyntaxKind nextTokenKind, STNode moduleNameStart) {
        List<STNode> moduleNameParts = new ArrayList<>();
        moduleNameParts.add(moduleNameStart);

        while (!isEndOfImportModuleName(nextTokenKind)) {
            moduleNameParts.add(parseDotToken());
            moduleNameParts.add(parseIdentifier(ParserRuleContext.IMPORT_MODULE_NAME));
            nextTokenKind = peek().kind;
        }

        return STNodeFactory.createNodeList(moduleNameParts);
    }

    private boolean isEndOfImportModuleName(SyntaxKind nextTokenKind) {
        return nextTokenKind != SyntaxKind.DOT_TOKEN && nextTokenKind != SyntaxKind.IDENTIFIER_TOKEN;
    }

    private boolean isEndOfImportDecl(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case SEMICOLON_TOKEN:
            case PUBLIC_KEYWORD:
            case FUNCTION_KEYWORD:
            case TYPE_KEYWORD:
            case ABSTRACT_KEYWORD:
            case CONST_KEYWORD:
            case EOF_TOKEN:
            case SERVICE_KEYWORD:
            case IMPORT_KEYWORD:
            case FINAL_KEYWORD:
            case TRANSACTIONAL_KEYWORD:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse version component of a import declaration.
     * <p>
     * <code>version-decl := version sem-ver</code>
     *
     * @return Parsed node
     */
    private STNode parseVersion() {
        STToken nextToken = peek();
        return parseVersion(nextToken.kind);
    }

    private STNode parseVersion(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case VERSION_KEYWORD:
                STNode versionKeyword = parseVersionKeyword();
                STNode versionNumber = parseVersionNumber();
                return STNodeFactory.createImportVersionNode(versionKeyword, versionNumber);
            case AS_KEYWORD:
            case SEMICOLON_TOKEN:
                return STNodeFactory.createEmptyNode();
            default:
                if (isEndOfImportDecl(nextTokenKind)) {
                    return STNodeFactory.createEmptyNode();
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.IMPORT_VERSION_DECL);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseVersion(solution.tokenKind);
        }

    }

    /**
     * Parse version keywrod.
     *
     * @return Parsed node
     */
    private STNode parseVersionKeyword() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.VERSION_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(peek(), ParserRuleContext.VERSION_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse version number.
     * <p>
     * <code>sem-ver := major-num [. minor-num [. patch-num]]
     * <br/>
     * major-num := DecimalNumber
     * <br/>
     * minor-num := DecimalNumber
     * <br/>
     * patch-num := DecimalNumber
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseVersionNumber() {
        STToken nextToken = peek();
        return parseVersionNumber(nextToken.kind);
    }

    private STNode parseVersionNumber(SyntaxKind nextTokenKind) {
        STNode majorVersion;
        switch (nextTokenKind) {
            case DECIMAL_INTEGER_LITERAL:
                majorVersion = parseMajorVersion();
                break;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.VERSION_NUMBER);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseVersionNumber(solution.tokenKind);
        }

        List<STNode> versionParts = new ArrayList<>();
        versionParts.add(majorVersion);

        STNode minorVersion = parseMinorVersion();
        if (minorVersion != null) {
            versionParts.add(minorVersion);

            STNode patchVersion = parsePatchVersion();
            if (patchVersion != null) {
                versionParts.add(patchVersion);
            }
        }

        return STNodeFactory.createNodeList(versionParts);

    }

    private STNode parseMajorVersion() {
        return parseDecimalIntLiteral(ParserRuleContext.MAJOR_VERSION);
    }

    private STNode parseMinorVersion() {
        return parseSubVersion(ParserRuleContext.MINOR_VERSION);
    }

    private STNode parsePatchVersion() {
        return parseSubVersion(ParserRuleContext.PATCH_VERSION);
    }

    /**
     * Parse decimal literal.
     *
     * @param context Context in which the decimal literal is used.
     * @return Parsed node
     */
    private STNode parseDecimalIntLiteral(ParserRuleContext context) {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.DECIMAL_INTEGER_LITERAL) {
            return consume();
        } else {
            Solution sol = recover(peek(), context);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse sub version. i.e: minor-version/patch-version.
     *
     * @param context Context indicating what kind of sub-version is being parsed.
     * @return Parsed node
     */
    private STNode parseSubVersion(ParserRuleContext context) {
        STToken nextToken = peek();
        return parseSubVersion(nextToken.kind, context);
    }

    private STNode parseSubVersion(SyntaxKind nextTokenKind, ParserRuleContext context) {
        switch (nextTokenKind) {
            case AS_KEYWORD:
            case SEMICOLON_TOKEN:
                return null;
            case DOT_TOKEN:
                STNode leadingDot = parseDotToken();
                STNode versionNumber = parseDecimalIntLiteral(context);
                return STNodeFactory.createImportSubVersionNode(leadingDot, versionNumber);
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.IMPORT_SUB_VERSION);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseSubVersion(solution.tokenKind, context);
        }
    }

    /**
     * Parse import prefix declaration.
     * <p>
     * <code>import-prefix-decl := as import-prefix
     * <br/>
     * import-prefix := a identifier | _
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseImportPrefixDecl() {
        STToken token = peek();
        return parseImportPrefixDecl(token.kind);
    }

    private STNode parseImportPrefixDecl(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case AS_KEYWORD:
                STNode asKeyword = parseAsKeyword();
                STNode prefix = parseImportPrefix();
                return STNodeFactory.createImportPrefixNode(asKeyword, prefix);
            case SEMICOLON_TOKEN:
                return STNodeFactory.createEmptyNode();
            default:
                if (isEndOfImportDecl(nextTokenKind)) {
                    return STNodeFactory.createEmptyNode();
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.IMPORT_PREFIX_DECL);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseImportPrefixDecl(solution.tokenKind);
        }
    }

    /**
     * Parse <code>as</code> keyword.
     *
     * @return Parsed node
     */
    private STNode parseAsKeyword() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.AS_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(peek(), ParserRuleContext.AS_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse import prefix.
     *
     * @return Parsed node
     */
    private STNode parseImportPrefix() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(peek(), ParserRuleContext.IMPORT_PREFIX);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse top level node, given the modifier that precedes it.
     *
     * @param qualifier Qualifier that precedes the top level node
     * @return Parsed node
     */
    private STNode parseTopLevelNode(STNode metadata, STNode qualifier) {
        STToken token = peek();
        return parseTopLevelNode(token.kind, metadata, qualifier);
    }

    /**
     * Parse top level node given the next token kind and the modifier that precedes it.
     *
     * @param tokenKind Next token kind
     * @param qualifier Qualifier that precedes the top level node
     * @return Parsed top-level node
     */
    private STNode parseTopLevelNode(SyntaxKind tokenKind, STNode metadata, STNode qualifier) {
        switch (tokenKind) {
            case EOF_TOKEN:
                reportInvalidQualifier(qualifier);
                return null;
            case FUNCTION_KEYWORD:
            case TRANSACTIONAL_KEYWORD:
                // ANything starts with a function keyword could be a function definition
                // or a module-var-decl with function type desc.
                List<STNode> qualifiers = new ArrayList<>();
                if (qualifier != null) {
                    qualifiers.add(qualifier);
                }
                return parseFuncDefOrFuncTypeDesc(ParserRuleContext.TOP_LEVEL_FUNC_DEF_OR_FUNC_TYPE_DESC, metadata,
                        false, qualifiers);
            case TYPE_KEYWORD:
                return parseModuleTypeDefinition(metadata, getQualifier(qualifier));
            case LISTENER_KEYWORD:
                return parseListenerDeclaration(metadata, getQualifier(qualifier));
            case CONST_KEYWORD:
                return parseConstantDeclaration(metadata, getQualifier(qualifier));
            case ANNOTATION_KEYWORD:
                STNode constKeyword = STNodeFactory.createEmptyNode();
                return parseAnnotationDeclaration(metadata, getQualifier(qualifier), constKeyword);
            case IMPORT_KEYWORD:
                reportInvalidQualifier(qualifier);
                // TODO log error for metadata
                return parseImportDecl();
            case XMLNS_KEYWORD:
                reportInvalidQualifier(qualifier);
                // TODO log error for metadata
                return parseXMLNamespaceDeclaration(true);
            case FINAL_KEYWORD:
                reportInvalidQualifier(qualifier);
                STNode finalKeyword = parseFinalKeyword();
                return parseVariableDecl(metadata, finalKeyword, true);
            case SERVICE_KEYWORD:
                if (isServiceDeclStart(ParserRuleContext.TOP_LEVEL_NODE, 1)) {
                    reportInvalidQualifier(qualifier);
                    return parseServiceDecl(metadata);
                }
                return parseModuleVarDecl(metadata, qualifier);
            case ENUM_KEYWORD:
                return parseEnumDeclaration(metadata, getQualifier(qualifier));
            case IDENTIFIER_TOKEN:
                // Here we assume that after recovering, we'll never reach here.
                // Otherwise the tokenOffset will not be 1.
                if (isModuleVarDeclStart(1)) {
                    return parseModuleVarDecl(metadata, qualifier);
                }
                // fall through
            default:
                if (isTypeStartingToken(tokenKind) && tokenKind != SyntaxKind.IDENTIFIER_TOKEN) {
                    return parseModuleVarDecl(metadata, qualifier);
                }

                STToken token = peek();
                Solution solution =
                        recover(token, ParserRuleContext.TOP_LEVEL_NODE_WITHOUT_MODIFIER, metadata, qualifier);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                if (solution.action == Action.KEEP) {
                    // If the solution is {@link Action#KEEP}, that means next immediate token is
                    // at the correct place, but some token after that is not. There only one such
                    // cases here, which is the `case IDENTIFIER_TOKEN`. So accept it, and continue.
                    return parseModuleVarDecl(metadata, qualifier);
                }

                return parseTopLevelNode(solution.tokenKind, metadata, qualifier);
        }

    }

    private STNode parseModuleVarDecl(STNode metadata, STNode qualifier) {
        reportInvalidQualifier(qualifier);
        STNode finalKeyword = STNodeFactory.createEmptyNode();
        return parseVariableDecl(metadata, finalKeyword, true);
    }

    private STNode getQualifier(STNode qualifier) {
        return qualifier == null ? STNodeFactory.createEmptyNode() : qualifier;
    }

    private void reportInvalidQualifier(STNode qualifier) {
        if (qualifier != null && qualifier.kind != SyntaxKind.NONE) {
            addInvalidNodeToNextToken(qualifier, DiagnosticErrorCode.ERROR_INVALID_QUALIFIER,
                    qualifier.toString().trim());
        }
    }

    /**
     * Parse access modifiers.
     *
     * @return Parsed node
     */
    private STNode parseQualifier() {
        STToken token = peek();
        if (token.kind == SyntaxKind.PUBLIC_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.PUBLIC_KEYWORD);
            return sol.recoveredNode;
        }
    }

    private STNode parseFuncDefinition(STNode metadata, boolean isObjectMethod, List<STNode> qualifiers) {
        startContext(ParserRuleContext.FUNC_DEF);
        STNode functionKeyword = parseFunctionKeyword();
        STNode funcDef = parseFunctionKeywordRhs(metadata, functionKeyword, true, isObjectMethod, qualifiers);
        return funcDef;
    }

    /**
     * Parse function definition for the function type descriptor.
     * <p>
     * <code>
     * function-defn := FUNCTION identifier function-signature function-body
     * <br/>
     * function-type-descriptor := function function-signature
     * </code>
     *
     * @param metadata Metadata
     * @param qualifiers qualifier list
     * @return Parsed node
     */
    private STNode parseFuncDefOrFuncTypeDesc(ParserRuleContext context, STNode metadata,
                                              boolean isObjectMethod, List<STNode> qualifiers) {
        return parseFuncDefOrFuncTypeDesc(peek().kind, context, metadata, isObjectMethod, qualifiers);
    }
    private STNode parseFuncDefOrFuncTypeDesc(SyntaxKind nextTokenKind, ParserRuleContext context, STNode metadata,
                                              boolean isObjectMethod, List<STNode> qualifiers) {
        switch (nextTokenKind) {
            case TRANSACTIONAL_KEYWORD:
                qualifiers.add(parseTransactionalKeyword());
                break;
            case FUNCTION_KEYWORD:
                break;
            default:
                Solution solution = recover(peek(), context, context, metadata, isObjectMethod, qualifiers);

                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }
                return parseFuncDefOrFuncTypeDesc(solution.tokenKind, context, metadata, isObjectMethod, qualifiers);
        }
        return parseFuncDefOrFuncTypeDesc(metadata, isObjectMethod, qualifiers);
    }

    private STNode parseFuncDefOrFuncTypeDesc(STNode metadata, boolean isObjectMethod, List<STNode> qualifiers) {
        startContext(ParserRuleContext.FUNC_DEF_OR_FUNC_TYPE);
        STNode functionKeyword = parseFunctionKeyword();
        STNode funcDefOrType = parseFunctionKeywordRhs(metadata, functionKeyword, false, isObjectMethod, qualifiers);
        return funcDefOrType;
    }

    private STNode parseFunctionKeywordRhs(STNode metadata, STNode functionKeyword, boolean isFuncDef,
                                           boolean isObjectMethod, List<STNode> qualifiers) {
        // If the function name is present, treat this as a function def
        if (isFuncDef) {
            STNode name = parseFunctionName();
            switchContext(ParserRuleContext.FUNC_DEF);
            STNode funcSignature = parseFuncSignature(false);
            STNode funcDef = createFuncDefOrMethodDecl(metadata, functionKeyword, isObjectMethod, name, funcSignature,
                    qualifiers);
            endContext();
            return funcDef;
        }

        return parseFunctionKeywordRhs(metadata, functionKeyword, isObjectMethod, qualifiers);
    }

    private STNode parseFunctionKeywordRhs(STNode metadata, STNode functionKeyword, boolean isObjectMethod,
                                           List<STNode> qualifiers) {
        return parseFunctionKeywordRhs(peek().kind, metadata, functionKeyword, isObjectMethod, qualifiers);
    }

    private STNode parseFunctionKeywordRhs(SyntaxKind nextTokenKind, STNode metadata, STNode functionKeyword,
                                           boolean isObjectMethod, List<STNode> qualifiers) {
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN:
                STNode name = parseFunctionName();
                switchContext(ParserRuleContext.FUNC_DEF);
                STNode funcSignature = parseFuncSignature(false);
                STNode funcDef = createFuncDefOrMethodDecl(metadata, functionKeyword, isObjectMethod, name,
                        funcSignature, qualifiers);
                endContext();
                return funcDef;
            case OPEN_PAREN_TOKEN:
                funcSignature = parseFuncSignature(true);
                return parseReturnTypeDescRhs(metadata, functionKeyword, funcSignature, isObjectMethod, qualifiers);
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.FUNCTION_KEYWORD_RHS, metadata, functionKeyword,
                        isObjectMethod, qualifiers);

                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }
                return parseFunctionKeywordRhs(solution.tokenKind, metadata, functionKeyword, isObjectMethod,
                        qualifiers);
        }
    }

    private STNode createFuncDefOrMethodDecl(STNode metadata, STNode functionKeyword, boolean isObjectMethod,
                                             STNode name, STNode funcSignature, List<STNode> qualifiers) {
        STNode body = parseFunctionBody(isObjectMethod);
        STNode qualifierList = STNodeFactory.createNodeList(qualifiers);
        if (body.kind == SyntaxKind.SEMICOLON_TOKEN) {
            return STNodeFactory.createMethodDeclarationNode(metadata, qualifierList, functionKeyword, name,
                    funcSignature, body);
        }

        if (isObjectMethod) {
            return STNodeFactory.createObjectMethodDefinitionNode(metadata, qualifierList,
                    functionKeyword, name, funcSignature, body);
        }

        return STNodeFactory.createFunctionDefinitionNode(metadata, qualifierList, functionKeyword, name,
                funcSignature, body);
    }

    /**
     * Parse function signature.
     * <p>
     * <code>
     * function-signature := ( param-list ) return-type-descriptor
     * <br/>
     * return-type-descriptor := [ returns [annots] type-descriptor ]
     * </code>
     *
     * @param isParamNameOptional Whether the parameter names are optional
     * @return Function signature node
     */
    private STNode parseFuncSignature(boolean isParamNameOptional) {
        STNode openParenthesis = parseOpenParenthesis(ParserRuleContext.OPEN_PARENTHESIS);
        STNode parameters = parseParamList(isParamNameOptional);
        STNode closeParenthesis = parseCloseParenthesis();
        endContext(); // end param-list
        STNode returnTypeDesc = parseFuncReturnTypeDescriptor();
        return STNodeFactory.createFunctionSignatureNode(openParenthesis, parameters, closeParenthesis, returnTypeDesc);
    }

    private STNode parseReturnTypeDescRhs(STNode metadata, STNode functionKeyword, STNode funcSignature,
                                          boolean isObjectMethod, List<STNode> qualifiers) {
        switch (peek().kind) {
            // var-decl with function type
            case SEMICOLON_TOKEN:
            case IDENTIFIER_TOKEN:
            case OPEN_BRACKET_TOKEN:
                // Parse the remaining as var-decl, because its the only module-level construct
                // that can start with a func-type-desc. Constants cannot have func-type-desc.
                endContext(); // end the func-type
                STNode typeDesc = STNodeFactory.createFunctionTypeDescriptorNode(functionKeyword, funcSignature);

                if (isObjectMethod) {
                    STNode readonlyQualifier = STNodeFactory.createEmptyNode();
                    STNode fieldName = parseVariableName();
                    if (qualifiers.size() == 0) {
                        return parseObjectFieldRhs(metadata, STNodeFactory.createEmptyNode(), readonlyQualifier,
                                typeDesc, fieldName);
                    } else {
                        return parseObjectFieldRhs(metadata, qualifiers.get(0), readonlyQualifier, typeDesc, fieldName);
                    }
                }

                startContext(ParserRuleContext.VAR_DECL_STMT);
                STNode typedBindingPattern = parseTypedBindingPatternTypeRhs(typeDesc, ParserRuleContext.VAR_DECL_STMT);
                if (qualifiers.size() == 0) {
                    return parseVarDeclRhs(metadata, STNodeFactory.createEmptyNode(), typedBindingPattern, true);
                } else {
                    return parseVarDeclRhs(metadata, qualifiers.get(0), typedBindingPattern, true);
                }
            case OPEN_BRACE_TOKEN: // function body block
            case EQUAL_TOKEN: // external function
                break;
            default:
                break;
        }

        // Treat as function definition.

        // We reach this method only if the func-name is not present.
        STNode name = SyntaxErrors.createMissingTokenWithDiagnostics(SyntaxKind.IDENTIFIER_TOKEN,
                DiagnosticErrorCode.ERROR_MISSING_FUNCTION_NAME);

        // Function definition cannot have missing param-names. So validate it.
        funcSignature = validateAndGetFuncParams((STFunctionSignatureNode) funcSignature);

        STNode funcDef =
                createFuncDefOrMethodDecl(metadata, functionKeyword, isObjectMethod, name, funcSignature, qualifiers);
        endContext();
        return funcDef;
    }

    /**
     * Validate the param list and return. If there are params without param-name,
     * then this method will create a new set of params with missing param-name
     * and return.
     *
     * @param signature Function signature
     * @return
     */
    private STNode validateAndGetFuncParams(STFunctionSignatureNode signature) {
        STNode parameters = signature.parameters;
        int paramCount = parameters.bucketCount();
        int index = 0;
        for (; index < paramCount; index++) {
            STNode param = parameters.childInBucket(index);
            switch (param.kind) {
                case REQUIRED_PARAM:
                    STRequiredParameterNode requiredParam = (STRequiredParameterNode) param;
                    if (isEmpty(requiredParam.paramName)) {
                        break;
                    }
                    continue;
                case DEFAULTABLE_PARAM:
                    STDefaultableParameterNode defaultableParam = (STDefaultableParameterNode) param;
                    if (isEmpty(defaultableParam.paramName)) {
                        break;
                    }
                    continue;
                case REST_PARAM:
                    STRestParameterNode restParam = (STRestParameterNode) param;
                    if (isEmpty(restParam.paramName)) {
                        break;
                    }
                    continue;
                default:
                    continue;
            }

            // Stop processing any further.
            break;
        }

        // This is an optimization. If none of the parameters have errors,
        // then we can return the same parameter as is. Here we have optimized
        // the happy path.
        if (index == paramCount) {
            return signature;
        }

        // Otherwise, we create a new param list. This overhead is acceptable, since
        // we reach here only for a erroneous edge-case where a function-definition
        // has a missing name, along with some parameter with a missing name.

        // Add the parameters up to the erroneous param, to the new list.
        STNode updatedParams = getUpdatedParamList(parameters, index);
        return STNodeFactory.createFunctionSignatureNode(signature.openParenToken, updatedParams,
                signature.closeParenToken, signature.returnTypeDesc);
    }

    private STNode getUpdatedParamList(STNode parameters, int index) {
        int paramCount = parameters.bucketCount();
        int newIndex = 0;
        ArrayList<STNode> newParams = new ArrayList<>();
        for (; newIndex < index; newIndex++) {
            newParams.add(parameters.childInBucket(index));
        }

        // From there onwards, create a new param with missing param-name, if the
        // param name is empty. Otherwise, add the same param as is, to the new list.
        for (; newIndex < paramCount; newIndex++) {
            STNode param = parameters.childInBucket(newIndex);
            STNode paramName = STNodeFactory.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN);
            switch (param.kind) {
                case REQUIRED_PARAM:
                    STRequiredParameterNode requiredParam = (STRequiredParameterNode) param;
                    if (isEmpty(requiredParam.paramName)) {
                        param = STNodeFactory.createRequiredParameterNode(requiredParam.leadingComma,
                                requiredParam.annotations, requiredParam.visibilityQualifier, requiredParam.typeName,
                                paramName);
                    }
                    break;
                case DEFAULTABLE_PARAM:
                    STDefaultableParameterNode defaultableParam = (STDefaultableParameterNode) param;
                    if (isEmpty(defaultableParam.paramName)) {
                        param = STNodeFactory.createDefaultableParameterNode(defaultableParam.leadingComma,
                                defaultableParam.annotations, defaultableParam.visibilityQualifier,
                                defaultableParam.typeName, paramName, defaultableParam.equalsToken,
                                defaultableParam.expression);
                    }
                    break;
                case REST_PARAM:
                    STRestParameterNode restParam = (STRestParameterNode) param;
                    if (isEmpty(restParam.paramName)) {
                        param = STNodeFactory.createRestParameterNode(restParam.leadingComma, restParam.annotations,
                                restParam.typeName, restParam.ellipsisToken, paramName);
                    }
                    break;
                default:
                    break;
            }
            newParams.add(param);
        }

        return STNodeFactory.createNodeList(newParams);
    }

    private boolean isEmpty(STNode node) {
        return !SyntaxUtils.isSTNodePresent(node);
    }

    /**
     * Parse function keyword. Need to validate the token before consuming,
     * since we can reach here while recovering.
     *
     * @return Parsed node
     */
    private STNode parseFunctionKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.FUNCTION_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FUNCTION_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse function name.
     *
     * @return Parsed node
     */
    private STNode parseFunctionName() {
        STToken token = peek();
        if (token.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FUNC_NAME);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse open parenthesis.
     *
     * @param ctx Context of the parenthesis
     * @return Parsed node
     */
    private STNode parseOpenParenthesis(ParserRuleContext ctx) {
        STToken token = peek();
        if (token.kind == SyntaxKind.OPEN_PAREN_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ctx);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse close parenthesis.
     *
     * @return Parsed node
     */
    private STNode parseCloseParenthesis() {
        STToken token = peek();
        if (token.kind == SyntaxKind.CLOSE_PAREN_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CLOSE_PARENTHESIS);
            return sol.recoveredNode;
        }
    }

    /**
     * <p>
     * Parse parameter list.
     * </p>
     * <code>
     * param-list := required-params [, defaultable-params] [, rest-param]
     *     <br/>&nbsp;| defaultable-params [, rest-param]
     *     <br/>&nbsp;| [rest-param]
     * <br/><br/>
     * required-params := required-param (, required-param)*
     * <br/><br/>
     * required-param := [annots] [public] type-descriptor [param-name]
     * <br/><br/>
     * defaultable-params := defaultable-param (, defaultable-param)*
     * <br/><br/>
     * defaultable-param := [annots] [public] type-descriptor [param-name] default-value
     * <br/><br/>
     * rest-param := [annots] type-descriptor ... [param-name]
     * <br/><br/>
     * param-name := identifier
     * </code>
     *
     * @param isParamNameOptional Whether the param names in the signature is optional or not.
     * @return Parsed node
     */
    private STNode parseParamList(boolean isParamNameOptional) {
        startContext(ParserRuleContext.PARAM_LIST);
        STToken token = peek();
        if (isEndOfParametersList(token.kind)) {
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse the first parameter. Comma precedes the first parameter doesn't exist.
        ArrayList<STNode> paramsList = new ArrayList<>();
        STNode startingComma = STNodeFactory.createEmptyNode();
        startContext(ParserRuleContext.REQUIRED_PARAM);
        STNode firstParam = parseParameter(startingComma, SyntaxKind.REQUIRED_PARAM, isParamNameOptional);
        SyntaxKind prevParamKind = firstParam.kind;
        paramsList.add(firstParam);

        // Parse follow-up parameters.
        boolean paramOrderErrorPresent = false;
        token = peek();
        while (!isEndOfParametersList(token.kind)) {
            if (prevParamKind == SyntaxKind.DEFAULTABLE_PARAM) {
                startContext(ParserRuleContext.DEFAULTABLE_PARAM);
            } else {
                startContext(ParserRuleContext.REQUIRED_PARAM);
            }

            STNode paramEnd = parseParameterRhs();
            if (paramEnd == null) {
                endContext();
                break;
            }

            // context is ended inside parseParameter() method
            STNode param = parseParameter(paramEnd, prevParamKind, isParamNameOptional);
            if (paramOrderErrorPresent) {
                updateLastNodeInListWithInvalidNode(paramsList, param, null);
            } else {
                DiagnosticCode paramOrderError = validateParamOrder(param, prevParamKind);
                if (paramOrderError == null) {
                    paramsList.add(param);
                } else {
                    paramOrderErrorPresent = true;
                    updateLastNodeInListWithInvalidNode(paramsList, param, paramOrderError);
                }
            }

            prevParamKind = param.kind;
            token = peek();
        }

        return STNodeFactory.createNodeList(paramsList);
    }

    /**
     * Return the appropriate {@code DiagnosticCode} if there are parameter order issues.
     *
     * @param param the new parameter
     * @param prevParamKind the SyntaxKind of the previously added parameter
     */
    private DiagnosticCode validateParamOrder(STNode param, SyntaxKind prevParamKind) {
        if (prevParamKind == SyntaxKind.REST_PARAM) {
            return DiagnosticErrorCode.ERROR_PARAMETER_AFTER_THE_REST_PARAMETER;
        } else if (prevParamKind == SyntaxKind.DEFAULTABLE_PARAM && param.kind == SyntaxKind.REQUIRED_PARAM) {
            return DiagnosticErrorCode.ERROR_REQUIRED_PARAMETER_AFTER_THE_DEFAULTABLE_PARAMETER;
        } else {
            return null;
        }
    }

    private boolean isNodeWithSyntaxKindInList(List<STNode> nodeList, SyntaxKind kind) {
        for (STNode node : nodeList) {
            if (node.kind == kind) {
                return true;
            }
        }
        return false;
    }

    private STNode parseParameterRhs() {
        return parseParameterRhs(peek().kind);
    }

    private STNode parseParameterRhs(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_PAREN_TOKEN:
                return null;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.PARAM_END);

                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseParameterRhs(solution.tokenKind);
        }

    }

    /**
     * Parse a single parameter. Parameter can be a required parameter, a defaultable
     * parameter, or a rest parameter.
     *
     * @param prevParamKind Kind of the parameter that precedes current parameter
     * @param leadingComma Comma that occurs before the param
     * @param isParamNameOptional Whether the param names in the signature is optional or not.
     * @return Parsed node
     */
    private STNode parseParameter(STNode leadingComma, SyntaxKind prevParamKind, boolean isParamNameOptional) {
        STToken token = peek();
        return parseParameter(token.kind, prevParamKind, leadingComma, 1, isParamNameOptional);
    }

    private STNode parseParameter(SyntaxKind prevParamKind, STNode leadingComma, int nextTokenOffset,
                                  boolean isParamNameOptional) {
        return parseParameter(peek().kind, prevParamKind, leadingComma, nextTokenOffset, isParamNameOptional);
    }

    private STNode parseParameter(SyntaxKind nextTokenKind, SyntaxKind prevParamKind, STNode leadingComma,
                                  int nextTokenOffset, boolean isParamNameOptional) {
        STNode annots;
        switch (nextTokenKind) {
            case AT_TOKEN:
                annots = parseOptionalAnnotations(nextTokenKind);
                nextTokenKind = peek().kind;
                break;
            case PUBLIC_KEYWORD:
            case IDENTIFIER_TOKEN:
                annots = STNodeFactory.createEmptyNodeList();
                break;
            default:
                if (isTypeStartingToken(nextTokenKind)) {
                    annots = STNodeFactory.createNodeList(new ArrayList<>());
                    break;
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.PARAMETER_START, prevParamKind, leadingComma,
                        nextTokenOffset, isParamNameOptional);

                if (solution.action == Action.KEEP) {
                    // If the solution is {@link Action#KEEP}, that means next immediate token is
                    // at the correct place, but some token after that is not. There only one such
                    // cases here, which is the `case IDENTIFIER_TOKEN`. So accept it, and continue.
                    annots = STNodeFactory.createEmptyNodeList();
                    break;
                }

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                // Since we come here after recovering by insertion, then the current token becomes the next token.
                // So the nextNextToken offset becomes 1.
                return parseParameter(solution.tokenKind, prevParamKind, leadingComma, 0, isParamNameOptional);
        }

        return parseParamGivenAnnots(nextTokenKind, prevParamKind, leadingComma, annots, 1, isParamNameOptional);
    }

    private STNode parseParamGivenAnnots(SyntaxKind prevParamKind, STNode leadingComma, STNode annots,
                                         int nextNextTokenOffset, boolean isFuncDef) {
        return parseParamGivenAnnots(peek().kind, prevParamKind, leadingComma, annots, nextNextTokenOffset, isFuncDef);
    }

    private STNode parseParamGivenAnnots(SyntaxKind nextTokenKind, SyntaxKind prevParamKind, STNode leadingComma,
                                         STNode annots, int nextTokenOffset, boolean isParamNameOptional) {
        STNode qualifier;
        switch (nextTokenKind) {
            case PUBLIC_KEYWORD:
                qualifier = parseQualifier();
                break;
            case IDENTIFIER_TOKEN:
                qualifier = STNodeFactory.createEmptyNode();
                break;
            case AT_TOKEN: // Annotations can't reach here
            default:
                if (isTypeStartingToken(nextTokenKind) && nextTokenKind != SyntaxKind.IDENTIFIER_TOKEN) {
                    qualifier = STNodeFactory.createEmptyNode();
                    break;
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.PARAMETER_WITHOUT_ANNOTS, prevParamKind,
                        leadingComma, annots, nextTokenOffset, isParamNameOptional);

                if (solution.action == Action.KEEP) {
                    // If the solution is {@link Action#KEEP}, that means next immediate token is
                    // at the correct place, but some token after that is not. There only one such
                    // cases here, which is the `case IDENTIFIER_TOKEN`. So accept it, and continue.
                    qualifier = STNodeFactory.createEmptyNode();
                    break;
                }

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                // Since we come here after recovering by insertion, then the current token becomes the next token.
                // So the nextNextToken offset becomes 1.
                return parseParamGivenAnnots(solution.tokenKind, prevParamKind, leadingComma, annots, 0,
                        isParamNameOptional);
        }

        return parseParamGivenAnnotsAndQualifier(prevParamKind, leadingComma, annots, qualifier, isParamNameOptional);
    }

    private STNode parseParamGivenAnnotsAndQualifier(SyntaxKind prevParamKind, STNode leadingComma, STNode annots,
                                                     STNode qualifier, boolean isParamNameOptional) {
        STNode type = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_BEFORE_IDENTIFIER);
        STNode param = parseAfterParamType(prevParamKind, leadingComma, annots, qualifier, type, isParamNameOptional);
        endContext();
        return param;
    }

    private STNode parseAfterParamType(SyntaxKind prevParamKind, STNode leadingComma, STNode annots, STNode qualifier,
                                       STNode type, boolean isParamNameOptional) {
        STToken token = peek();
        return parseAfterParamType(token.kind, prevParamKind, leadingComma, annots, qualifier, type,
                isParamNameOptional);
    }

    private STNode parseAfterParamType(SyntaxKind tokenKind, SyntaxKind prevParamKind, STNode leadingComma,
                                       STNode annots, STNode qualifier, STNode type, boolean isParamNameOptional) {
        STNode paramName;
        switch (tokenKind) {
            case ELLIPSIS_TOKEN:
                switchContext(ParserRuleContext.REST_PARAM);
                reportInvalidQualifier(qualifier);
                STNode ellipsis = parseEllipsis();
                if (isParamNameOptional && peek().kind != SyntaxKind.IDENTIFIER_TOKEN) {
                    paramName = STNodeFactory.createEmptyNode();
                } else {
                    paramName = parseVariableName();
                }
                return STNodeFactory.createRestParameterNode(leadingComma, annots, type, ellipsis, paramName);
            case IDENTIFIER_TOKEN:
                paramName = parseVariableName();
                return parseParameterRhs(prevParamKind, leadingComma, annots, qualifier, type, paramName);
            case EQUAL_TOKEN:
                if (!isParamNameOptional) {
                    break;
                }
                // If this is a function-type-desc, then param name is optional, and may not exist
                paramName = STNodeFactory.createEmptyNode();
                return parseParameterRhs(prevParamKind, leadingComma, annots, qualifier, type, paramName);
            default:
                if (!isParamNameOptional) {
                    break;
                }
                // If this is a function-type-desc, then param name is optional, and may not exist
                paramName = STNodeFactory.createEmptyNode();
                return parseParameterRhs(prevParamKind, leadingComma, annots, qualifier, type, paramName);
        }
        STToken token = peek();
        Solution solution = recover(token, ParserRuleContext.AFTER_PARAMETER_TYPE, prevParamKind, leadingComma, annots,
                qualifier, type, isParamNameOptional);

        // If the parser recovered by inserting a token, then try to re-parse the same
        // rule with the inserted token. This is done to pick the correct branch
        // to continue the parsing.
        if (solution.action == Action.REMOVE) {
            return solution.recoveredNode;
        }

        return parseAfterParamType(solution.tokenKind, prevParamKind, leadingComma, annots, qualifier, type,
                isParamNameOptional);

    }

    /**
     * Parse ellipsis.
     *
     * @return Parsed node
     */
    private STNode parseEllipsis() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ELLIPSIS_TOKEN) {
            return consume(); // parse '...'
        } else {
            Solution sol = recover(token, ParserRuleContext.ELLIPSIS);
            return sol.recoveredNode;
        }
    }

    /**
     * <p>
     * Parse the right hand side of a required/defaultable parameter.
     * </p>
     * <code>parameter-rhs := [= expression]</code>
     *
     * @param leadingComma Comma that precedes this parameter
     * @param prevParamKind Kind of the parameter that precedes current parameter
     * @param annots Annotations attached to the parameter
     * @param qualifier Visibility qualifier
     * @param type Type descriptor
     * @param paramName Name of the parameter
     * @return Parsed parameter node
     */
    private STNode parseParameterRhs(SyntaxKind prevParamKind, STNode leadingComma, STNode annots, STNode qualifier,
                                     STNode type, STNode paramName) {
        STToken token = peek();
        return parseParameterRhs(token.kind, prevParamKind, leadingComma, annots, qualifier, type, paramName);
    }

    private STNode parseParameterRhs(SyntaxKind tokenKind, SyntaxKind prevParamKind, STNode leadingComma, STNode annots,
                                     STNode qualifier, STNode type, STNode paramName) {
        // Required parameters
        if (isEndOfParameter(tokenKind)) {
            return STNodeFactory.createRequiredParameterNode(leadingComma, annots, qualifier, type, paramName);
        } else if (tokenKind == SyntaxKind.EQUAL_TOKEN) {
            // If we were processing required params so far and found a defualtable
            // parameter, then switch the context to defaultable params.
            if (prevParamKind == SyntaxKind.REQUIRED_PARAM) {
                switchContext(ParserRuleContext.DEFAULTABLE_PARAM);
            }

            // Defaultable parameters
            STNode equal = parseAssignOp();
            STNode expr = parseExpression();
            return STNodeFactory.createDefaultableParameterNode(leadingComma, annots, qualifier, type, paramName, equal,
                    expr);
        } else {
            STToken token = peek();
            Solution solution = recover(token, ParserRuleContext.PARAMETER_NAME_RHS, prevParamKind, leadingComma,
                    annots, qualifier, type, paramName);

            // If the parser recovered by inserting a token, then try to re-parse the same
            // rule with the inserted token. This is done to pick the correct branch
            // to continue the parsing.
            if (solution.action == Action.REMOVE) {
                return solution.recoveredNode;
            }

            return parseParameterRhs(solution.tokenKind, prevParamKind, leadingComma, annots, qualifier, type,
                    paramName);
        }
    }

    /**
     * Parse comma.
     *
     * @return Parsed node
     */
    private STNode parseComma() {
        STToken token = peek();
        if (token.kind == SyntaxKind.COMMA_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.COMMA);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse return type descriptor of a function. A return type descriptor has the following structure.
     *
     * <code>return-type-descriptor := [ returns annots type-descriptor ]</code>
     *
     * @return Parsed node
     */
    private STNode parseFuncReturnTypeDescriptor() {
        return parseFuncReturnTypeDescriptor(peek().kind);
    }

    private STNode parseFuncReturnTypeDescriptor(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case OPEN_BRACE_TOKEN: // func-body block
            case EQUAL_TOKEN: // external func
                return STNodeFactory.createEmptyNode();
            case RETURNS_KEYWORD:
                break;
            default:
                STToken nextNextToken = getNextNextToken(nextTokenKind);
                if (nextNextToken.kind == SyntaxKind.RETURNS_KEYWORD) {
                    break;
                }

                return STNodeFactory.createEmptyNode();
        }

        STNode returnsKeyword = parseReturnsKeyword();
        STNode annot = parseOptionalAnnotations();
        STNode type = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_RETURN_TYPE_DESC);
        return STNodeFactory.createReturnTypeDescriptorNode(returnsKeyword, annot, type);
    }

    /**
     * Parse 'returns' keyword.
     *
     * @return Return-keyword node
     */
    private STNode parseReturnsKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.RETURNS_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.RETURNS_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * <p>
     * Parse a type descriptor. A type descriptor has the following structure.
     * </p>
     * <code>type-descriptor :=
     *      &nbsp;simple-type-descriptor<br/>
     *      &nbsp;| structured-type-descriptor<br/>
     *      &nbsp;| behavioral-type-descriptor<br/>
     *      &nbsp;| singleton-type-descriptor<br/>
     *      &nbsp;| union-type-descriptor<br/>
     *      &nbsp;| optional-type-descriptor<br/>
     *      &nbsp;| any-type-descriptor<br/>
     *      &nbsp;| anydata-type-descriptor<br/>
     *      &nbsp;| byte-type-descriptor<br/>
     *      &nbsp;| json-type-descriptor<br/>
     *      &nbsp;| type-descriptor-reference<br/>
     *      &nbsp;| ( type-descriptor )
     * <br/>
     * type-descriptor-reference := qualified-identifier</code>
     *
     * @return Parsed node
     */
    private STNode parseTypeDescriptor(ParserRuleContext context) {
        return parseTypeDescriptor(context, false, false);
    }

    private STNode parseTypeDescriptorInExpression(ParserRuleContext context, boolean isInConditionalExpr) {
        return parseTypeDescriptor(context, false, isInConditionalExpr);
    }

    private STNode parseTypeDescriptor(ParserRuleContext context, boolean isTypedBindingPattern,
                                       boolean isInConditionalExpr) {
        startContext(context);
        STNode typeDesc = parseTypeDescriptorInternal(context, isTypedBindingPattern, isInConditionalExpr);
        endContext();
        return typeDesc;
    }

    private STNode parseTypeDescriptorInternal(ParserRuleContext context, boolean isInConditionalExpr) {
        return parseTypeDescriptorInternal(context, false, isInConditionalExpr);
    }

    private STNode parseTypeDescriptorInternal(ParserRuleContext context, boolean isTypedBindingPattern,
                                               boolean isInConditionalExpr) {
        STToken token = peek();
        STNode typeDesc = parseTypeDescriptorInternal(token.kind, context, isInConditionalExpr);

        // var is parsed as a built-in simple type. However, since var is not allowed everywhere,
        // validate it here. This is done to give better error messages.
        if (typeDesc.kind == SyntaxKind.VAR_TYPE_DESC &&
                context != ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN) {
            STToken missingToken = STNodeFactory.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN);
            missingToken = SyntaxErrors.cloneWithLeadingInvalidNodeMinutiae(missingToken, typeDesc,
                    DiagnosticErrorCode.ERROR_INVALID_USAGE_OF_VAR);
            typeDesc = STNodeFactory.createSimpleNameReferenceNode(missingToken);
        }

        return parseComplexTypeDescriptor(typeDesc, context, isTypedBindingPattern);
    }

    /**
     * This will handle the parsing of optional,array,union type desc to infinite length.
     *
     * @param typeDesc
     *
     * @return Parsed type descriptor node
     */
    private STNode parseComplexTypeDescriptor(STNode typeDesc, ParserRuleContext context,
                                              boolean isTypedBindingPattern) {
        STToken nextToken = peek();
        switch (nextToken.kind) {
            case QUESTION_MARK_TOKEN:
                // If next token after a type descriptor is '?' then it is an optional type descriptor
                if (context == ParserRuleContext.TYPE_DESC_IN_EXPRESSION &&
                        !isValidTypeContinuationToken(getNextNextToken(nextToken.kind)) &&
                        isValidExprStart(getNextNextToken(nextToken.kind).kind)) {
                    return typeDesc;
                }
                return parseComplexTypeDescriptor(parseOptionalTypeDescriptor(typeDesc), context,
                        isTypedBindingPattern);
            case OPEN_BRACKET_TOKEN:
                // If next token after a type descriptor is '[' then it is an array type descriptor
                if (isTypedBindingPattern) { // checking for typedesc parsing originating at typed-binding-pattern
                    return typeDesc;
                }
                return parseComplexTypeDescriptor(parseArrayTypeDescriptor(typeDesc), context, isTypedBindingPattern);
            case PIPE_TOKEN:
                // If next token after a type descriptor is '|' then it is an union type descriptor
                return parseUnionTypeDescriptor(typeDesc, context, isTypedBindingPattern);
            case BITWISE_AND_TOKEN:
                // If next token after a type descriptor is '&' then it is intersection type descriptor
                return parseIntersectionTypeDescriptor(typeDesc, context, isTypedBindingPattern);
            default:
                return typeDesc;
        }
    }

    private boolean isValidTypeContinuationToken(STToken nextToken) {
        switch (nextToken.kind) {
            case QUESTION_MARK_TOKEN:
            case OPEN_BRACKET_TOKEN:
            case PIPE_TOKEN:
            case BITWISE_AND_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode validateForUsageOfVar(STNode typeDesc) {
        if (typeDesc.kind != SyntaxKind.VAR_TYPE_DESC) {
            return typeDesc;
        }

        STToken missingToken = STNodeFactory.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN);
        missingToken = SyntaxErrors.cloneWithLeadingInvalidNodeMinutiae(missingToken, typeDesc,
                DiagnosticErrorCode.ERROR_INVALID_USAGE_OF_VAR);
        return STNodeFactory.createSimpleNameReferenceNode(missingToken);
    }

    /**
     * <p>
     * Parse a type descriptor, given the next token kind.
     * </p>
     * If the preceding token is <code>?</code> then it is an optional type descriptor
     *
     * @param tokenKind Next token kind
     * @param context Current context
     * @param isInConditionalExpr
     * @return Parsed node
     */
    private STNode parseTypeDescriptorInternal(SyntaxKind tokenKind, ParserRuleContext context,
                                               boolean isInConditionalExpr) {
        switch (tokenKind) {
            case IDENTIFIER_TOKEN:
                return parseTypeReference(isInConditionalExpr);
            case RECORD_KEYWORD:
                // Record type descriptor
                return parseRecordTypeDescriptor();
            case READONLY_KEYWORD:
                STToken nextNextToken = getNextNextToken(tokenKind);
                SyntaxKind nextNextTokenKind = nextNextToken.kind;
                if (nextNextTokenKind != SyntaxKind.OBJECT_KEYWORD &&
                        nextNextTokenKind != SyntaxKind.ABSTRACT_KEYWORD &&
                        nextNextTokenKind != SyntaxKind.CLIENT_KEYWORD) {
                    return parseSimpleTypeDescriptor();
                }
                // Else fall through
            case OBJECT_KEYWORD:
            case ABSTRACT_KEYWORD:
            case CLIENT_KEYWORD:
                // Object type descriptor
                return parseObjectTypeDescriptor();
            case OPEN_PAREN_TOKEN:
                return parseNilOrParenthesisedTypeDesc();
            case MAP_KEYWORD: // map type desc
            case FUTURE_KEYWORD: // future type desc
                return parseParameterizedTypeDescriptor();
            case TYPEDESC_KEYWORD: // typedesc type desc
                return parseTypedescTypeDescriptor();
            case ERROR_KEYWORD: // error type descriptor
                return parseErrorTypeDescriptor();
            case XML_KEYWORD: // typedesc type desc
                return parseXmlTypeDescriptor();
            case STREAM_KEYWORD: // stream type desc
                return parseStreamTypeDescriptor();
            case TABLE_KEYWORD: // table type desc
                return parseTableTypeDescriptor();
            case FUNCTION_KEYWORD:
                return parseFunctionTypeDesc();
            case OPEN_BRACKET_TOKEN:
                return parseTupleTypeDesc();
            case DISTINCT_KEYWORD:
                return parseDistinctTypeDesc(context);
            default:
                if (isSingletonTypeDescStart(tokenKind, true)) {
                    return parseSingletonTypeDesc();
                }
                if (isSimpleType(tokenKind)) {
                    return parseSimpleTypeDescriptor();
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.TYPE_DESCRIPTOR, context, isInConditionalExpr);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTypeDescriptorInternal(solution.tokenKind, context, isInConditionalExpr);
        }
    }

    /**
     * Parse distinct type descriptor.
     * <p>
     * <code>
     * distinct-type-descriptor := distinct type-descriptor
     * </code>
     *
     * @param context Context in which the type desc is used.
     * @return Distinct type descriptor
     */
    private STNode parseDistinctTypeDesc(ParserRuleContext context) {
        STNode distinctKeyword = parseDistinctKeyword();
        STNode typeDesc = parseTypeDescriptor(context);
        return STNodeFactory.createDistinctTypeDescriptorNode(distinctKeyword, typeDesc);
    }

    private STNode parseDistinctKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.DISTINCT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.DISTINCT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    private STNode parseNilOrParenthesisedTypeDesc() {
        STNode openParen = parseOpenParenthesis(ParserRuleContext.OPEN_PARENTHESIS);
        return parseNilOrParenthesisedTypeDescRhs(openParen);
    }

    private STNode parseNilOrParenthesisedTypeDescRhs(STNode openParen) {
        return parseNilOrParenthesisedTypeDescRhs(peek().kind, openParen);
    }

    private STNode parseNilOrParenthesisedTypeDescRhs(SyntaxKind nextTokenKind, STNode openParen) {
        STNode closeParen;
        switch (nextTokenKind) {
            case CLOSE_PAREN_TOKEN:
                closeParen = parseCloseParenthesis();
                return STNodeFactory.createNilTypeDescriptorNode(openParen, closeParen);
            default:
                if (isTypeStartingToken(nextTokenKind)) {
                    STNode typedesc = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_PARENTHESIS);
                    closeParen = parseCloseParenthesis();
                    return STNodeFactory.createParenthesisedTypeDescriptorNode(openParen, typedesc, closeParen);
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.NIL_OR_PARENTHESISED_TYPE_DESC_RHS, openParen);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseNilOrParenthesisedTypeDescRhs(solution.tokenKind, openParen);
        }
    }

    /**
     * Parse simple type descriptor.
     *
     * @return Parsed node
     */
    private STNode parseSimpleTypeDescriptor() {
        STToken node = peek();
        if (isSimpleType(node.kind)) {
            STToken token = consume();
            return createBuiltinSimpleNameReference(token);
        } else {
            Solution sol = recover(peek(), ParserRuleContext.SIMPLE_TYPE_DESCRIPTOR);
            STNode recoveredNode = sol.recoveredNode;
            return createBuiltinSimpleNameReference(recoveredNode);
        }
    }

    private STNode createBuiltinSimpleNameReference(STNode token) {
        SyntaxKind typeKind = getTypeSyntaxKind(token.kind);
        return STNodeFactory.createBuiltinSimpleNameReferenceNode(typeKind, token);
    }

    /**
     * <p>
     * Parse function body. A function body has the following structure.
     * </p>
     * <code>
     * function-body := function-body-block | external-function-body
     * external-function-body := = annots external ;
     * function-body-block := { [default-worker-init, named-worker-decl+] default-worker }
     * </code>
     *
     * @param isObjectMethod Flag indicating whether this is an object-method
     * @return Parsed node
     */
    private STNode parseFunctionBody(boolean isObjectMethod) {
        STToken token = peek();
        return parseFunctionBody(token.kind, isObjectMethod);
    }

    /**
     * Parse function body, given the next token kind.
     *
     * @param tokenKind Next token kind
     * @param isObjectMethod Flag indicating whether this is an object-method
     * @return Parsed node
     */
    protected STNode parseFunctionBody(SyntaxKind tokenKind, boolean isObjectMethod) {
        switch (tokenKind) {
            case EQUAL_TOKEN:
                return parseExternalFunctionBody();
            case OPEN_BRACE_TOKEN:
                return parseFunctionBodyBlock(false);
            case RIGHT_DOUBLE_ARROW_TOKEN:
                return parseExpressionFuncBody(false, false);
            case SEMICOLON_TOKEN:
                if (isObjectMethod) {
                    return parseSemicolon();
                }

                // else fall through
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.FUNC_BODY, isObjectMethod);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.

                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                // If the recovered token is not something that can be re-parsed,
                // then don't try to re-parse the same rule.
                if (solution.tokenKind == SyntaxKind.NONE) {
                    return STNodeFactory.createMissingToken(solution.tokenKind);
                }

                return parseFunctionBody(solution.tokenKind, isObjectMethod);
        }
    }

    /**
     * <p>
     * Parse function body block. A function body block has the following structure.
     * </p>
     *
     * <code>
     * function-body-block := { [default-worker-init, named-worker-decl+] default-worker }<br/>
     * default-worker-init := sequence-stmt<br/>
     * default-worker := sequence-stmt<br/>
     * named-worker-decl := worker worker-name return-type-descriptor { sequence-stmt }<br/>
     * worker-name := identifier<br/>
     * </code>
     *
     * @param isAnonFunc Flag indicating whether the func body belongs to an anonymous function
     * @return Parsed node
     */
    private STNode parseFunctionBodyBlock(boolean isAnonFunc) {
        startContext(ParserRuleContext.FUNC_BODY_BLOCK);
        STNode openBrace = parseOpenBrace();
        STToken token = peek();

        ArrayList<STNode> firstStmtList = new ArrayList<>();
        ArrayList<STNode> workers = new ArrayList<>();
        ArrayList<STNode> secondStmtList = new ArrayList<>();

        ParserRuleContext currentCtx = ParserRuleContext.DEFAULT_WORKER_INIT;
        boolean hasNamedWorkers = false;
        while (!isEndOfFuncBodyBlock(token.kind, isAnonFunc)) {
            STNode stmt = parseStatement();
            if (stmt == null) {
                break;
            }

            switch (currentCtx) {
                case DEFAULT_WORKER_INIT:
                    if (stmt.kind != SyntaxKind.NAMED_WORKER_DECLARATION) {
                        firstStmtList.add(stmt);
                        break;
                    }
                    // We come here when we find the first named-worker-decl.
                    // Switch to parsing named-workers.
                    currentCtx = ParserRuleContext.NAMED_WORKERS;
                    hasNamedWorkers = true;
                    // fall through
                case NAMED_WORKERS:
                    if (stmt.kind == SyntaxKind.NAMED_WORKER_DECLARATION) {
                        workers.add(stmt);
                        break;
                    }
                    // Otherwise switch to parsing default-worker
                    currentCtx = ParserRuleContext.DEFAULT_WORKER;
                    // fall through
                case DEFAULT_WORKER:
                default:
                    if (stmt.kind == SyntaxKind.NAMED_WORKER_DECLARATION) {
                        updateLastNodeInListWithInvalidNode(secondStmtList, stmt,
                                DiagnosticErrorCode.ERROR_NAMED_WORKER_NOT_ALLOWED_HERE);
                        break;
                    }
                    secondStmtList.add(stmt);
                    break;
            }
            token = peek();
        }

        STNode namedWorkersList;
        STNode statements;
        if (hasNamedWorkers) {
            STNode workerInitStatements = STNodeFactory.createNodeList(firstStmtList);
            STNode namedWorkers = STNodeFactory.createNodeList(workers);
            namedWorkersList = STNodeFactory.createNamedWorkerDeclarator(workerInitStatements, namedWorkers);
            statements = STNodeFactory.createNodeList(secondStmtList);
        } else {
            namedWorkersList = STNodeFactory.createEmptyNode();
            statements = STNodeFactory.createNodeList(firstStmtList);
        }

        STNode closeBrace = parseCloseBrace();
        endContext();
        return STNodeFactory.createFunctionBodyBlockNode(openBrace, namedWorkersList, statements, closeBrace);
    }

    private boolean isEndOfFuncBodyBlock(SyntaxKind nextTokenKind, boolean isAnonFunc) {
        if (isAnonFunc) {
            switch (nextTokenKind) {
                case CLOSE_BRACE_TOKEN:
                case CLOSE_PAREN_TOKEN:
                case CLOSE_BRACKET_TOKEN:
                case OPEN_BRACE_TOKEN:
                case SEMICOLON_TOKEN:
                case COMMA_TOKEN:
                case PUBLIC_KEYWORD:
                case EOF_TOKEN:
                case EQUAL_TOKEN:
                case BACKTICK_TOKEN:
                    return true;
                default:
                    break;
            }
        }

        return isEndOfStatements();
    }

    private boolean isEndOfRecordTypeNode(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case TYPE_KEYWORD:
            case PUBLIC_KEYWORD:
            default:
                return endOfModuleLevelNode(1);
        }
    }

    private boolean isEndOfObjectTypeNode() {
        return endOfModuleLevelNode(1, true);
    }

    private boolean isEndOfStatements() {
        switch (peek().kind) {
            case RESOURCE_KEYWORD:
                return true;
            default:
                return endOfModuleLevelNode(1);
        }
    }

    private boolean endOfModuleLevelNode(int peekIndex) {
        return endOfModuleLevelNode(peekIndex, false);
    }

    private boolean endOfModuleLevelNode(int peekIndex, boolean isObject) {
        switch (peek(peekIndex).kind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
            case CLOSE_BRACE_PIPE_TOKEN:
            case IMPORT_KEYWORD:
            case CONST_KEYWORD:
            case ANNOTATION_KEYWORD:
            case LISTENER_KEYWORD:
                return true;
            case SERVICE_KEYWORD:
                return isServiceDeclStart(ParserRuleContext.OBJECT_MEMBER, 1);
            case PUBLIC_KEYWORD:
                return endOfModuleLevelNode(peekIndex + 1, isObject);
            case FUNCTION_KEYWORD:
                if (isObject) {
                    return false;
                }

                // if function keyword follows by a identifier treat is as
                // the function name. Only function def can have func-name
                return peek(peekIndex + 1).kind == SyntaxKind.IDENTIFIER_TOKEN;
            default:
                return false;
        }
    }

    /**
     * Check whether the given token is an end of a parameter.
     *
     * @param tokenKind Next token kind
     * @return <code>true</code> if the token represents an end of a parameter. <code>false</code> otherwise
     */
    private boolean isEndOfParameter(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case CLOSE_PAREN_TOKEN:
            case CLOSE_BRACKET_TOKEN:
            case SEMICOLON_TOKEN:
            case COMMA_TOKEN:
            case RETURNS_KEYWORD:
            case TYPE_KEYWORD:
            case IF_KEYWORD:
            case WHILE_KEYWORD:
            case AT_TOKEN:
                return true;
            default:
                return endOfModuleLevelNode(1);
        }
    }

    /**
     * Check whether the given token is an end of a parameter-list.
     *
     * @param tokenKind Next token kind
     * @return <code>true</code> if the token represents an end of a parameter-list. <code>false</code> otherwise
     */
    private boolean isEndOfParametersList(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case CLOSE_PAREN_TOKEN:
            case SEMICOLON_TOKEN:
            case RETURNS_KEYWORD:
            case TYPE_KEYWORD:
            case IF_KEYWORD:
            case WHILE_KEYWORD:
                return true;
            default:
                return endOfModuleLevelNode(1);
        }
    }

    /**
     * Parse type reference or variable reference.
     *
     * @return Parsed node
     */
    private STNode parseStatementStartIdentifier() {
        return parseQualifiedIdentifier(ParserRuleContext.TYPE_NAME_OR_VAR_NAME);
    }

    /**
     * Parse variable name.
     *
     * @return Parsed node
     */
    private STNode parseVariableName() {
        STToken token = peek();
        if (token.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(peek(), ParserRuleContext.VARIABLE_NAME);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse open brace.
     *
     * @return Parsed node
     */
    private STNode parseOpenBrace() {
        STToken token = peek();
        if (token.kind == SyntaxKind.OPEN_BRACE_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.OPEN_BRACE);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse close brace.
     *
     * @return Parsed node
     */
    private STNode parseCloseBrace() {
        STToken token = peek();
        if (token.kind == SyntaxKind.CLOSE_BRACE_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CLOSE_BRACE);
            return sol.recoveredNode;
        }
    }

    /**
     * <p>
     * Parse external function body. An external function body has the following structure.
     * </p>
     * <code>
     * external-function-body := = annots external ;
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseExternalFunctionBody() {
        startContext(ParserRuleContext.EXTERNAL_FUNC_BODY);
        STNode assign = parseAssignOp();
        return parseExternalFuncBodyRhs(assign);
    }

    private STNode parseExternalFuncBodyRhs(STNode assign) {
        STToken nextToken = peek();
        return parseExternalFuncBodyRhs(nextToken, assign);
    }

    private STNode parseExternalFuncBodyRhs(STToken nextToken, STNode assign) {
        STNode annotation;
        switch (nextToken.kind) {
            case AT_TOKEN:
                annotation = parseAnnotations();
                break;
            case EXTERNAL_KEYWORD:
                annotation = STNodeFactory.createNodeList();
                break;
            default:
                Solution solution = recover(nextToken, ParserRuleContext.EXTERNAL_FUNC_BODY_OPTIONAL_ANNOTS, assign);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.

                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseExternalFuncBodyRhs((STToken) solution.recoveredNode, assign);
        }

        STNode externalKeyword = parseExternalKeyword();
        STNode semicolon = parseSemicolon();
        endContext();
        return STNodeFactory.createExternalFunctionBodyNode(assign, annotation, externalKeyword, semicolon);
    }

    /**
     * Parse semicolon.
     *
     * @return Parsed node
     */
    private STNode parseSemicolon() {
        STToken token = peek();
        if (token.kind == SyntaxKind.SEMICOLON_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.SEMICOLON);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse <code>external</code> keyword.
     *
     * @return Parsed node
     */
    private STNode parseExternalKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.EXTERNAL_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.EXTERNAL_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /*
     * Operators
     */

    /**
     * Parse assign operator.
     *
     * @return Parsed node
     */
    private STNode parseAssignOp() {
        STToken token = peek();
        if (token.kind == SyntaxKind.EQUAL_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ASSIGN_OP);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse binary operator.
     *
     * @return Parsed node
     */
    private STNode parseBinaryOperator() {
        STToken token = peek();
        if (isBinaryOperator(token.kind)) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.BINARY_OPERATOR);
            return sol.recoveredNode;
        }
    }

    /**
     * Check whether the given token kind is a binary operator.
     *
     * @param kind STToken kind
     * @return <code>true</code> if the token kind refers to a binary operator. <code>false</code> otherwise
     */
    private boolean isBinaryOperator(SyntaxKind kind) {
        switch (kind) {
            case PLUS_TOKEN:
            case MINUS_TOKEN:
            case SLASH_TOKEN:
            case ASTERISK_TOKEN:
            case GT_TOKEN:
            case LT_TOKEN:
            case DOUBLE_EQUAL_TOKEN:
            case TRIPPLE_EQUAL_TOKEN:
            case LT_EQUAL_TOKEN:
            case GT_EQUAL_TOKEN:
            case NOT_EQUAL_TOKEN:
            case NOT_DOUBLE_EQUAL_TOKEN:
            case BITWISE_AND_TOKEN:
            case BITWISE_XOR_TOKEN:
            case PIPE_TOKEN:
            case LOGICAL_AND_TOKEN:
            case LOGICAL_OR_TOKEN:
            case PERCENT_TOKEN:
            case DOUBLE_LT_TOKEN:
            case DOUBLE_GT_TOKEN:
            case TRIPPLE_GT_TOKEN:
            case ELLIPSIS_TOKEN:
            case DOUBLE_DOT_LT_TOKEN:
            case ELVIS_TOKEN:
            case EQUALS_KEYWORD:
                return true;
            default:
                return false;
        }
    }

    /**
     * Get the precedence of a given operator.
     *
     * @param binaryOpKind Operator kind
     * @return Precedence of the given operator
     */
    private OperatorPrecedence getOpPrecedence(SyntaxKind binaryOpKind) {
        switch (binaryOpKind) {
            case ASTERISK_TOKEN: // multiplication
            case SLASH_TOKEN: // division
            case PERCENT_TOKEN: // remainder
                return OperatorPrecedence.MULTIPLICATIVE;
            case PLUS_TOKEN:
            case MINUS_TOKEN:
                return OperatorPrecedence.ADDITIVE;
            case GT_TOKEN:
            case LT_TOKEN:
            case GT_EQUAL_TOKEN:
            case LT_EQUAL_TOKEN:
            case IS_KEYWORD:
                return OperatorPrecedence.BINARY_COMPARE;
            case DOT_TOKEN:
            case OPEN_BRACKET_TOKEN:
            case OPEN_PAREN_TOKEN:
            case ANNOT_CHAINING_TOKEN:
            case OPTIONAL_CHAINING_TOKEN:
            case DOT_LT_TOKEN:
            case SLASH_LT_TOKEN:
            case DOUBLE_SLASH_DOUBLE_ASTERISK_LT_TOKEN:
            case SLASH_ASTERISK_TOKEN:
                return OperatorPrecedence.MEMBER_ACCESS;
            case DOUBLE_EQUAL_TOKEN:
            case TRIPPLE_EQUAL_TOKEN:
            case NOT_EQUAL_TOKEN:
            case NOT_DOUBLE_EQUAL_TOKEN:
            case EQUALS_KEYWORD:
                return OperatorPrecedence.EQUALITY;
            case BITWISE_AND_TOKEN:
                return OperatorPrecedence.BITWISE_AND;
            case BITWISE_XOR_TOKEN:
                return OperatorPrecedence.BITWISE_XOR;
            case PIPE_TOKEN:
                return OperatorPrecedence.BITWISE_OR;
            case LOGICAL_AND_TOKEN:
                return OperatorPrecedence.LOGICAL_AND;
            case LOGICAL_OR_TOKEN:
                return OperatorPrecedence.LOGICAL_OR;
            case RIGHT_ARROW_TOKEN:
                return OperatorPrecedence.REMOTE_CALL_ACTION;
            case RIGHT_DOUBLE_ARROW_TOKEN:
                return OperatorPrecedence.ANON_FUNC_OR_LET;
            case SYNC_SEND_TOKEN:
                return OperatorPrecedence.ACTION;
            case DOUBLE_LT_TOKEN:
            case DOUBLE_GT_TOKEN:
            case TRIPPLE_GT_TOKEN:
                return OperatorPrecedence.SHIFT;
            case ELLIPSIS_TOKEN:
            case DOUBLE_DOT_LT_TOKEN:
                return OperatorPrecedence.RANGE;
            case ELVIS_TOKEN:
                return OperatorPrecedence.ELVIS_CONDITIONAL;
            case QUESTION_MARK_TOKEN:
            case COLON_TOKEN:
                return OperatorPrecedence.CONDITIONAL;
            default:
                throw new UnsupportedOperationException("Unsupported binary operator '" + binaryOpKind + "'");
        }
    }

    /**
     * <p>
     * Get the operator kind to insert during recovery, given the precedence level.
     * </p>
     *
     * @param opPrecedenceLevel Precedence of the given operator
     * @return Kind of the operator to insert
     */
    private SyntaxKind getBinaryOperatorKindToInsert(OperatorPrecedence opPrecedenceLevel) {
        switch (opPrecedenceLevel) {
            case DEFAULT:
            case UNARY:
            case ACTION:
            case EXPRESSION_ACTION:
            case REMOTE_CALL_ACTION:
            case ANON_FUNC_OR_LET:
            case QUERY:
                // If the current precedence level is unary/action, then we return
                // the binary operator with closest precedence level to it.
                // Therefore fall through
            case MULTIPLICATIVE:
                return SyntaxKind.ASTERISK_TOKEN;
            case ADDITIVE:
                return SyntaxKind.PLUS_TOKEN;
            case SHIFT:
                return SyntaxKind.DOUBLE_LT_TOKEN;
            case RANGE:
                return SyntaxKind.ELLIPSIS_TOKEN;
            case BINARY_COMPARE:
                return SyntaxKind.LT_TOKEN;
            case EQUALITY:
                return SyntaxKind.DOUBLE_EQUAL_TOKEN;
            case BITWISE_AND:
                return SyntaxKind.BITWISE_AND_TOKEN;
            case BITWISE_XOR:
                return SyntaxKind.BITWISE_XOR_TOKEN;
            case BITWISE_OR:
                return SyntaxKind.PIPE_TOKEN;
            case LOGICAL_AND:
                return SyntaxKind.LOGICAL_AND_TOKEN;
            case LOGICAL_OR:
                return SyntaxKind.LOGICAL_OR_TOKEN;
            case ELVIS_CONDITIONAL:
                return SyntaxKind.ELVIS_TOKEN;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported operator precedence level'" + opPrecedenceLevel + "'");
        }
    }

    /**
     * <p>
     * Parse a module type definition.
     * </p>
     * <code>module-type-defn := metadata [public] type identifier type-descriptor ;</code>
     *
     * @param metadata Metadata
     * @param qualifier Visibility qualifier
     * @return Parsed node
     */
    private STNode parseModuleTypeDefinition(STNode metadata, STNode qualifier) {
        startContext(ParserRuleContext.MODULE_TYPE_DEFINITION);
        STNode typeKeyword = parseTypeKeyword();
        STNode typeName = parseTypeName();
        STNode typeDescriptor = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TYPE_DEF);
        STNode semicolon = parseSemicolon();
        endContext();
        return STNodeFactory.createTypeDefinitionNode(metadata, qualifier, typeKeyword, typeName, typeDescriptor,
                semicolon);
    }

    /**
     * Parse type keyword.
     *
     * @return Parsed node
     */
    private STNode parseTypeKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.TYPE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.TYPE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse type name.
     *
     * @return Parsed node
     */
    private STNode parseTypeName() {
        STToken token = peek();
        if (token.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.TYPE_NAME);
            return sol.recoveredNode;
        }
    }

    /**
     * <p>
     * Parse record type descriptor. A record type descriptor body has the following structure.
     * </p>
     *
     * <code>record-type-descriptor := inclusive-record-type-descriptor | exclusive-record-type-descriptor
     * <br/><br/>inclusive-record-type-descriptor := record { field-descriptor* }
     * <br/><br/>exclusive-record-type-descriptor := record {| field-descriptor* [record-rest-descriptor] |}
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseRecordTypeDescriptor() {
        startContext(ParserRuleContext.RECORD_TYPE_DESCRIPTOR);
        STNode recordKeyword = parseRecordKeyword();
        STNode bodyStartDelimiter = parseRecordBodyStartDelimiter();

        boolean isInclusive = bodyStartDelimiter.kind == SyntaxKind.OPEN_BRACE_TOKEN;

        ArrayList<STNode> recordFields = new ArrayList<>();
        STToken token = peek();
        STNode recordRestDescriptor = null;
        while (!isEndOfRecordTypeNode(token.kind)) {
            STNode field = parseFieldOrRestDescriptor(isInclusive);
            if (field == null) {
                break;
            }
            token = peek();
            if (field.kind == SyntaxKind.RECORD_REST_TYPE) {
                recordRestDescriptor = field;
                break;
            }
            recordFields.add(field);
        }

        // Following loop will only run if there are more fields after the rest type descriptor.
        // Try to parse them and mark as invalid.
        while (recordRestDescriptor != null && !isEndOfRecordTypeNode(token.kind)) {
            STNode invalidField = parseFieldOrRestDescriptor(isInclusive);
            recordRestDescriptor = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(recordRestDescriptor, invalidField,
                    DiagnosticErrorCode.ERROR_MORE_RECORD_FIELDS_AFTER_REST_FIELD);
            token = peek();
        }

        STNode fields = STNodeFactory.createNodeList(recordFields);

        STNode bodyEndDelimiter = parseRecordBodyCloseDelimiter(bodyStartDelimiter.kind);
        endContext();

        return STNodeFactory.createRecordTypeDescriptorNode(recordKeyword, bodyStartDelimiter, fields,
                recordRestDescriptor, bodyEndDelimiter);
    }

    /**
     * Parse record body start delimiter.
     *
     * @return Parsed node
     */
    private STNode parseRecordBodyStartDelimiter() {
        STToken token = peek();
        return parseRecordBodyStartDelimiter(token.kind);
    }

    private STNode parseRecordBodyStartDelimiter(SyntaxKind kind) {
        switch (kind) {
            case OPEN_BRACE_PIPE_TOKEN:
                return parseClosedRecordBodyStart();
            case OPEN_BRACE_TOKEN:
                return parseOpenBrace();
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.RECORD_BODY_START);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseRecordBodyStartDelimiter(solution.tokenKind);
        }
    }

    /**
     * Parse closed-record body start delimiter.
     *
     * @return Parsed node
     */
    private STNode parseClosedRecordBodyStart() {
        STToken token = peek();
        if (token.kind == SyntaxKind.OPEN_BRACE_PIPE_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CLOSED_RECORD_BODY_START);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse record body close delimiter.
     *
     * @return Parsed node
     */
    private STNode parseRecordBodyCloseDelimiter(SyntaxKind startingDelimeter) {
        switch (startingDelimeter) {
            case OPEN_BRACE_PIPE_TOKEN:
                return parseClosedRecordBodyEnd();
            case OPEN_BRACE_TOKEN:
                return parseCloseBrace();
            default:
                // Ideally should never reach here.

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.RECORD_BODY_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseRecordBodyCloseDelimiter(solution.tokenKind);
        }
    }

    /**
     * Parse closed-record body end delimiter.
     *
     * @return Parsed node
     */
    private STNode parseClosedRecordBodyEnd() {
        STToken token = peek();
        if (token.kind == SyntaxKind.CLOSE_BRACE_PIPE_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CLOSED_RECORD_BODY_END);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse record keyword.
     *
     * @return Parsed node
     */
    private STNode parseRecordKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.RECORD_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.RECORD_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * <p>
     * Parse field descriptor or rest descriptor.
     * </p>
     *
     * <code>
     * <br/><br/>field-descriptor := individual-field-descriptor | record-type-reference
     * <br/><br/><br/>individual-field-descriptor := metadata type-descriptor field-name [? | default-value] ;
     * <br/><br/>field-name := identifier
     * <br/><br/>default-value := = expression
     * <br/><br/>record-type-reference := * type-reference ;
     * <br/><br/>record-rest-descriptor := type-descriptor ... ;
     * </code>
     *
     * @return Parsed node
     */

    private STNode parseFieldOrRestDescriptor(boolean isInclusive) {
        return parseFieldOrRestDescriptor(peek().kind, isInclusive);
    }

    private STNode parseFieldOrRestDescriptor(SyntaxKind nextTokenKind, boolean isInclusive) {
        switch (nextTokenKind) {
            case CLOSE_BRACE_TOKEN:
            case CLOSE_BRACE_PIPE_TOKEN:
                return null;
            case ASTERISK_TOKEN:
                // record-type-reference
                startContext(ParserRuleContext.RECORD_FIELD);
                STNode asterisk = consume();
                STNode type = parseTypeReference();
                STNode semicolonToken = parseSemicolon();
                endContext();
                return STNodeFactory.createTypeReferenceNode(asterisk, type, semicolonToken);
            case DOCUMENTATION_STRING:
            case AT_TOKEN:
                startContext(ParserRuleContext.RECORD_FIELD);
                STNode metadata = parseMetaData(nextTokenKind);
                nextTokenKind = peek().kind;
                return parseRecordField(nextTokenKind, isInclusive, metadata);
            default:
                if (isTypeStartingToken(nextTokenKind)) {
                    // individual-field-descriptor
                    startContext(ParserRuleContext.RECORD_FIELD);
                    metadata = createEmptyMetadata();
                    return parseRecordField(nextTokenKind, isInclusive, metadata);
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.RECORD_FIELD_OR_RECORD_END, isInclusive);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseFieldOrRestDescriptor(solution.tokenKind, isInclusive);
        }
    }

    private STNode parseRecordField(SyntaxKind nextTokenKind, boolean isInclusive, STNode metadata) {
        if (nextTokenKind != SyntaxKind.READONLY_KEYWORD) {
            STNode type = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_RECORD_FIELD);
            STNode fieldOrRestDesc = parseFieldDescriptor(isInclusive, metadata, type);
            endContext();
            return fieldOrRestDesc;
        }

        // If the readonly-keyword is present, check whether its qualifier
        // or the readonly-type-desc.
        STNode type;
        STNode fieldOrRestDesc;
        STNode readOnlyQualifier;
        readOnlyQualifier = parseReadonlyKeyword();
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            STNode fieldNameOrTypeDesc = parseQualifiedIdentifier(ParserRuleContext.RECORD_FIELD_NAME_OR_TYPE_NAME);
            if (fieldNameOrTypeDesc.kind == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                // readonly a:b
                // Then treat "a:b" as the type-desc
                type = fieldNameOrTypeDesc;
            } else {
                // readonly a
                nextToken = peek();
                switch (nextToken.kind) {
                    case SEMICOLON_TOKEN: // readonly a;
                    case EQUAL_TOKEN: // readonly a =
                        // Then treat "readonly" as type-desc, and "a" as the field-name
                        type = createBuiltinSimpleNameReference(readOnlyQualifier);
                        readOnlyQualifier = STNodeFactory.createEmptyNode();
                        STNode fieldName = ((STSimpleNameReferenceNode) fieldNameOrTypeDesc).name;
                        return parseFieldDescriptorRhs(metadata, readOnlyQualifier, type, fieldName);
                    default:
                        // else, treat a as the type-name
                        type = parseComplexTypeDescriptor(fieldNameOrTypeDesc,
                                ParserRuleContext.TYPE_DESC_IN_RECORD_FIELD, false);
                        break;
                }
            }
        } else if (nextToken.kind == SyntaxKind.ELLIPSIS_TOKEN) {
            // readonly ...
            type = createBuiltinSimpleNameReference(readOnlyQualifier);
            fieldOrRestDesc = parseFieldDescriptor(isInclusive, metadata, type);
            endContext();
            return fieldOrRestDesc;
        } else if (isTypeStartingToken(nextToken.kind)) {
            type = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_RECORD_FIELD);
        } else {
            readOnlyQualifier = createBuiltinSimpleNameReference(readOnlyQualifier);
            type = parseComplexTypeDescriptor(readOnlyQualifier, ParserRuleContext.TYPE_DESC_IN_RECORD_FIELD, false);
            readOnlyQualifier = STNodeFactory.createEmptyNode();
        }

        fieldOrRestDesc = parseIndividualRecordField(metadata, readOnlyQualifier, type);
        endContext();
        return fieldOrRestDesc;
    }

    private STNode parseFieldDescriptor(boolean isInclusive, STNode metadata, STNode type) {
        if (isInclusive) {
            STNode readOnlyQualifier = STNodeFactory.createEmptyNode();
            return parseIndividualRecordField(metadata, readOnlyQualifier, type);
        } else {
            return parseFieldOrRestDescriptorRhs(metadata, type);
        }
    }

    private STNode parseIndividualRecordField(STNode metadata, STNode readOnlyQualifier, STNode type) {
        STNode fieldName = parseVariableName();
        return parseFieldDescriptorRhs(metadata, readOnlyQualifier, type, fieldName);
    }

    /**
     * Parse type reference.
     * <code>type-reference := identifier | qualified-identifier</code>
     *
     * @return Type reference node
     */
    private STNode parseTypeReference() {
        STNode typeReference = parseTypeDescriptor(ParserRuleContext.TYPE_REFERENCE);
        if (typeReference.kind == SyntaxKind.SIMPLE_NAME_REFERENCE ||
                typeReference.kind == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
            return typeReference;
        }
        STNode emptyNameReference = STNodeFactory
                .createSimpleNameReferenceNode(SyntaxErrors.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN));
        emptyNameReference = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(emptyNameReference, typeReference,
                DiagnosticErrorCode.ONLY_TYPE_REFERENCE_ALLOWED_HERE_AS_TYPE_INCLUSIONS);
        return emptyNameReference;
    }

    private STNode parseTypeReference(boolean isInConditionalExpr) {
        return parseQualifiedIdentifier(ParserRuleContext.TYPE_REFERENCE, isInConditionalExpr);
    }

    /**
     * Parse identifier or qualified identifier.
     *
     * @return Identifier node
     */
    private STNode parseQualifiedIdentifier(ParserRuleContext currentCtx) {
        return parseQualifiedIdentifier(currentCtx, false);
    }

    private STNode parseQualifiedIdentifier(ParserRuleContext currentCtx, boolean isInConditionalExpr) {
        STToken token = peek();
        STNode typeRefOrPkgRef;
        if (token.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            typeRefOrPkgRef = consume();
        } else {
            Solution sol = recover(token, currentCtx, isInConditionalExpr);
            if (sol.action == Action.REMOVE) {
                return sol.recoveredNode;
            }

            if (sol.tokenKind != SyntaxKind.IDENTIFIER_TOKEN) {
                addInvalidTokenToNextToken(errorHandler.consumeInvalidToken());
                return parseQualifiedIdentifier(currentCtx, isInConditionalExpr);
            }

            typeRefOrPkgRef = sol.recoveredNode;
        }

        return parseQualifiedIdentifier(typeRefOrPkgRef, isInConditionalExpr);
    }

    /**
     * Parse identifier or qualified identifier, given the starting identifier.
     *
     * @param identifier Starting identifier
     * @return Parse node
     */
    private STNode parseQualifiedIdentifier(STNode identifier, boolean isInConditionalExpr) {
        STToken nextToken = peek(1);
        if (nextToken.kind != SyntaxKind.COLON_TOKEN) {
            return STNodeFactory.createSimpleNameReferenceNode(identifier);
        }

        STToken nextNextToken = peek(2);
        switch (nextNextToken.kind) {
            case IDENTIFIER_TOKEN:
                STToken colon = consume();
                STNode varOrFuncName = consume();
                return STNodeFactory.createQualifiedNameReferenceNode(identifier, colon, varOrFuncName);
            case MAP_KEYWORD:
                colon = consume();
                STToken mapKeyword = consume();
                STNode refName = STNodeFactory.createIdentifierToken(mapKeyword.text(), mapKeyword.leadingMinutiae(),
                        mapKeyword.trailingMinutiae(), mapKeyword.diagnostics());
                return STNodeFactory.createQualifiedNameReferenceNode(identifier, colon, refName);
            case COLON_TOKEN:
                // specially handle cases where there are more than one colon.
                addInvalidTokenToNextToken(errorHandler.consumeInvalidToken());
                return parseQualifiedIdentifier(identifier, isInConditionalExpr);
            default:
                if (isInConditionalExpr) {
                    return STNodeFactory.createSimpleNameReferenceNode(identifier);
                }

                colon = consume();
                varOrFuncName = SyntaxErrors.createMissingTokenWithDiagnostics(SyntaxKind.IDENTIFIER_TOKEN,
                        DiagnosticErrorCode.ERROR_MISSING_IDENTIFIER);
                return STNodeFactory.createQualifiedNameReferenceNode(identifier, colon, varOrFuncName);
        }
    }

    /**
     * Parse RHS of a field or rest type descriptor.
     *
     * @param metadata Metadata
     * @param type Type descriptor
     * @return Parsed node
     */
    private STNode parseFieldOrRestDescriptorRhs(STNode metadata, STNode type) {
        STToken token = peek();
        return parseFieldOrRestDescriptorRhs(token.kind, metadata, type);
    }

    private STNode parseFieldOrRestDescriptorRhs(SyntaxKind kind, STNode metadata, STNode type) {
        switch (kind) {
            case ELLIPSIS_TOKEN:
                // TODO: report error for invalid metadata
                STNode ellipsis = parseEllipsis();
                STNode semicolonToken = parseSemicolon();
                return STNodeFactory.createRecordRestDescriptorNode(type, ellipsis, semicolonToken);
            case IDENTIFIER_TOKEN:
                STNode readonlyQualifier = STNodeFactory.createEmptyNode();
                return parseIndividualRecordField(metadata, readonlyQualifier, type);
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.FIELD_OR_REST_DESCIPTOR_RHS, metadata, type);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseFieldOrRestDescriptorRhs(solution.tokenKind, metadata, type);
        }
    }

    /**
     * <p>
     * Parse field descriptor rhs.
     * </p>
     *
     * @param metadata Metadata
     * @param readonlyQualifier Readonly qualifier
     * @param type Type descriptor
     * @param fieldName Field name
     * @return Parsed node
     */
    private STNode parseFieldDescriptorRhs(STNode metadata, STNode readonlyQualifier, STNode type, STNode fieldName) {
        STToken token = peek();
        return parseFieldDescriptorRhs(token.kind, metadata, readonlyQualifier, type, fieldName);
    }

    /**
     * <p>
     * Parse field descriptor rhs.
     * </p>
     *
     * <code>
     * field-descriptor := [? | default-value] ;
     * <br/>default-value := = expression
     * </code>
     *
     * @param kind Kind of the next token
     * @param metadata Metadata
     * @param type Type descriptor
     * @param fieldName Field name
     * @return Parsed node
     */
    private STNode parseFieldDescriptorRhs(SyntaxKind kind, STNode metadata, STNode readonlyQualifier, STNode type,
                                           STNode fieldName) {
        switch (kind) {
            case SEMICOLON_TOKEN:
                STNode questionMarkToken = STNodeFactory.createEmptyNode();
                STNode semicolonToken = parseSemicolon();
                return STNodeFactory.createRecordFieldNode(metadata, readonlyQualifier, type, fieldName,
                        questionMarkToken, semicolonToken);
            case QUESTION_MARK_TOKEN:
                questionMarkToken = parseQuestionMark();
                semicolonToken = parseSemicolon();
                return STNodeFactory.createRecordFieldNode(metadata, readonlyQualifier, type, fieldName,
                        questionMarkToken, semicolonToken);
            case EQUAL_TOKEN:
                // parseRecordDefaultValue();
                STNode equalsToken = parseAssignOp();
                STNode expression = parseExpression();
                semicolonToken = parseSemicolon();
                return STNodeFactory.createRecordFieldWithDefaultValueNode(metadata, readonlyQualifier, type, fieldName,
                        equalsToken, expression, semicolonToken);
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.FIELD_DESCRIPTOR_RHS, metadata, readonlyQualifier,
                        type, fieldName);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseFieldDescriptorRhs(solution.tokenKind, metadata, readonlyQualifier, type, fieldName);
        }
    }

    /**
     * Parse question mark.
     *
     * @return Parsed node
     */
    private STNode parseQuestionMark() {
        STToken token = peek();
        if (token.kind == SyntaxKind.QUESTION_MARK_TOKEN) {
            return consume(); // '?' token
        } else {
            Solution sol = recover(token, ParserRuleContext.QUESTION_MARK);
            return sol.recoveredNode;
        }
    }

    /*
     * Statements
     */

    /**
     * Parse statements, until an end of a block is reached.
     *
     * @return Parsed node
     */
    private STNode parseStatements() {
        ArrayList<STNode> stmts = new ArrayList<>();
        return parseStatements(stmts);
    }

    private STNode parseStatements(ArrayList<STNode> stmts) {
        while (!isEndOfStatements()) {
            STNode stmt = parseStatement();
            if (stmt == null) {
                break;
            }

            if (stmt.kind == SyntaxKind.NAMED_WORKER_DECLARATION) {
                addInvalidNodeToNextToken(stmt, DiagnosticErrorCode.ERROR_NAMED_WORKER_NOT_ALLOWED_HERE);
                break;
            }
            stmts.add(stmt);
        }
        return STNodeFactory.createNodeList(stmts);
    }

    /**
     * Parse a single statement.
     *
     * @return Parsed node
     */
    protected STNode parseStatement() {
        STToken token = peek();
        return parseStatement(token.kind, 1);
    }

    private STNode parseStatement(SyntaxKind tokenKind, int nextTokenIndex) {
        STNode annots = null;
        switch (tokenKind) {
            case CLOSE_BRACE_TOKEN:
                // Returning null marks the end of statements
                return null;
            case SEMICOLON_TOKEN:
                addInvalidTokenToNextToken(errorHandler.consumeInvalidToken());
                return parseStatement();
            case AT_TOKEN:
                annots = parseOptionalAnnotations(tokenKind);
                tokenKind = peek().kind;
                break;
            case FINAL_KEYWORD:

                // Statements starts other than var-decl
            case IF_KEYWORD:
            case WHILE_KEYWORD:
            case PANIC_KEYWORD:
            case CONTINUE_KEYWORD:
            case BREAK_KEYWORD:
            case RETURN_KEYWORD:
            case TYPE_KEYWORD:
            case LOCK_KEYWORD:
            case OPEN_BRACE_TOKEN:
            case FORK_KEYWORD:
            case FOREACH_KEYWORD:
            case XMLNS_KEYWORD:
            case TRANSACTION_KEYWORD:
            case RETRY_KEYWORD:
            case ROLLBACK_KEYWORD:
            case MATCH_KEYWORD:

                // action-statements
            case CHECK_KEYWORD:
            case FAIL_KEYWORD:
            case CHECKPANIC_KEYWORD:
            case TRAP_KEYWORD:
            case START_KEYWORD:
            case FLUSH_KEYWORD:
            case LEFT_ARROW_TOKEN:
            case WAIT_KEYWORD:
            case COMMIT_KEYWORD:

                // Even-though worker is not a statement, we parse it as statements.
                // then validates it based on the context. This is done to provide
                // better error messages
            case WORKER_KEYWORD:
                break;
            default:
                // Var-decl-stmt start
                if (isTypeStartingToken(tokenKind)) {
                    break;
                }

                // Expression-stmt start
                if (isValidExpressionStart(tokenKind, nextTokenIndex)) {
                    break;
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.STATEMENT, nextTokenIndex);

                if (solution.action == Action.KEEP) {
                    // singleton type starting tokens can be correct one's hence keep them.
                    break;
                }

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseStatement(solution.tokenKind, nextTokenIndex);
        }

        return parseStatement(tokenKind, annots, nextTokenIndex);
    }

    private STNode getAnnotations(STNode nullbaleAnnot) {
        if (nullbaleAnnot != null) {
            return nullbaleAnnot;
        }

        return STNodeFactory.createEmptyNodeList();
    }

    private STNode parseStatement(STNode annots) {
        return parseStatement(peek().kind, annots, 1);
    }

    /**
     * Parse a single statement, given the next token kind.
     *
     * @param tokenKind Next token kind
     * @return Parsed node
     */
    private STNode parseStatement(SyntaxKind tokenKind, STNode annots, int nextTokenIndex) {
        // TODO: validate annotations: not every statement supports annots
        switch (tokenKind) {
            case CLOSE_BRACE_TOKEN:
                addInvalidNodeToNextToken(annots, DiagnosticErrorCode.ERROR_INVALID_ANNOTATIONS);
                // Returning null marks the end of statements
                return null;
            case SEMICOLON_TOKEN:
                addInvalidTokenToNextToken(errorHandler.consumeInvalidToken());
                return parseStatement(annots);
            case FINAL_KEYWORD:
                STNode finalKeyword = parseFinalKeyword();
                return parseVariableDecl(getAnnotations(annots), finalKeyword, false);
            case IF_KEYWORD:
                return parseIfElseBlock();
            case WHILE_KEYWORD:
                return parseWhileStatement();
            case PANIC_KEYWORD:
                return parsePanicStatement();
            case CONTINUE_KEYWORD:
                return parseContinueStatement();
            case BREAK_KEYWORD:
                return parseBreakStatement();
            case RETURN_KEYWORD:
                return parseReturnStatement();
            case TYPE_KEYWORD:
                return parseLocalTypeDefinitionStatement(getAnnotations(annots));
            case LOCK_KEYWORD:
                return parseLockStatement();
            case OPEN_BRACE_TOKEN:
                return parseStatementStartsWithOpenBrace();
            case WORKER_KEYWORD:
                // Even-though worker is not a statement, we parse it as statements.
                // then validates it based on the context. This is done to provide
                // better error messages
                return parseNamedWorkerDeclaration(getAnnotations(annots));
            case FORK_KEYWORD:
                return parseForkStatement();
            case FOREACH_KEYWORD:
                return parseForEachStatement();
            case START_KEYWORD:
            case CHECK_KEYWORD:
            case CHECKPANIC_KEYWORD:
            case FAIL_KEYWORD:
            case TRAP_KEYWORD:
            case FLUSH_KEYWORD:
            case LEFT_ARROW_TOKEN:
            case WAIT_KEYWORD:
            case FROM_KEYWORD:
            case COMMIT_KEYWORD:
                return parseExpressionStatement(tokenKind, getAnnotations(annots));
            case XMLNS_KEYWORD:
                return parseXMLNamespaceDeclaration(false);
            case TRANSACTION_KEYWORD:
                return parseTransactionStatement();
            case RETRY_KEYWORD:
                return parseRetryStatement();
            case ROLLBACK_KEYWORD:
                return parseRollbackStatement();
            case OPEN_BRACKET_TOKEN:
                // any statement starts with `[` can be either a var-decl with tuple type
                // or a destructuring assignment with list-binding-pattern.
                return parseStatementStartsWithOpenBracket(getAnnotations(annots), false);
            case FUNCTION_KEYWORD:
            case OPEN_PAREN_TOKEN:
            case IDENTIFIER_TOKEN:
                // Can be a singleton type or expression.
            case NIL_LITERAL:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
            case STRING_KEYWORD:
            case XML_KEYWORD:
                // These are statement starting tokens that has ambiguity between
                // being a type-desc or an expressions.
                return parseStmtStartsWithTypeOrExpr(tokenKind, getAnnotations(annots));
            case MATCH_KEYWORD:
                return parseMatchStatement();
            default:
                if (isValidExpressionStart(tokenKind, nextTokenIndex)) {
                    // These are expressions that are definitely not types.
                    return parseStatementStartWithExpr(getAnnotations(annots));
                }

                if (isTypeStartingToken(tokenKind)) {
                    // If the statement starts with a type, then its a var declaration.
                    // This is an optimization since if we know the next token is a type, then
                    // we can parse the var-def faster.
                    finalKeyword = STNodeFactory.createEmptyNode();
                    return parseVariableDecl(getAnnotations(annots), finalKeyword, false);
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.STATEMENT_WITHOUT_ANNOTS, annots, nextTokenIndex);

                if (solution.action == Action.KEEP) {
                    // singleton type starting tokens can be correct one's hence keep them.
                    finalKeyword = STNodeFactory.createEmptyNode();
                    return parseVariableDecl(getAnnotations(annots), finalKeyword, false);
                }
                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                // we come here if a token was inserted. Then the current token become the
                // next token. So 'nextTokenIndex = nextTokenIndex - 1'
                return parseStatement(solution.tokenKind, annots, nextTokenIndex - 1);
        }
    }

    /**
     * <p>
     * Parse variable declaration. Variable declaration can be a local or module level.
     * </p>
     *
     * <code>
     * local-var-decl-stmt := local-init-var-decl-stmt | local-no-init-var-decl-stmt
     * <br/><br/>
     * local-init-var-decl-stmt := [annots] [final] typed-binding-pattern = action-or-expr ;
     * <br/><br/>
     * local-no-init-var-decl-stmt := [annots] [final] type-descriptor variable-name ;
     * </code>
     *
     * @param annots Annotations or metadata
     * @param finalKeyword Final keyword
     * @return Parsed node
     */
    private STNode parseVariableDecl(STNode annots, STNode finalKeyword, boolean isModuleVar) {
        startContext(ParserRuleContext.VAR_DECL_STMT);
        STNode typeBindingPattern = parseTypedBindingPattern(ParserRuleContext.VAR_DECL_STMT);
        return parseVarDeclRhs(annots, finalKeyword, typeBindingPattern, isModuleVar);
    }

    /**
     * Parse final keyword.
     *
     * @return Parsed node
     */
    private STNode parseFinalKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.FINAL_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FINAL_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * <p>
     * Parse the right hand side of a variable declaration statement.
     * </p>
     * <code>
     * var-decl-rhs := ; | = action-or-expr ;
     * </code>
     *
     * @param metadata metadata
     * @param finalKeyword Final keyword
     * @param typedBindingPattern Typed binding pattern
     * @return Parsed node
     */
    private STNode parseVarDeclRhs(STNode metadata, STNode finalKeyword, STNode typedBindingPattern,
                                   boolean isModuleVar) {
        STToken token = peek();
        return parseVarDeclRhs(token.kind, metadata, finalKeyword, typedBindingPattern, isModuleVar);
    }

    /**
     * Parse the right hand side of a variable declaration statement, given the
     * next token kind.
     *
     * @param tokenKind Next token kind
     * @param metadata Metadata
     * @param finalKeyword Final keyword
     * @param typedBindingPattern Typed binding pattern
     * @param isModuleVar flag indicating whether the var is module level
     * @return Parsed node
     */
    private STNode parseVarDeclRhs(SyntaxKind tokenKind, STNode metadata, STNode finalKeyword,
                                   STNode typedBindingPattern, boolean isModuleVar) {
        STNode assign;
        STNode expr;
        STNode semicolon;
        switch (tokenKind) {
            case EQUAL_TOKEN:
                assign = parseAssignOp();
                if (isModuleVar) {
                    expr = parseExpression();
                } else {
                    expr = parseActionOrExpression();
                }
                semicolon = parseSemicolon();
                break;
            case SEMICOLON_TOKEN:
                assign = STNodeFactory.createEmptyNode();
                expr = STNodeFactory.createEmptyNode();
                semicolon = parseSemicolon();
                break;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.VAR_DECL_STMT_RHS, metadata, finalKeyword,
                        typedBindingPattern, isModuleVar);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseVarDeclRhs(solution.tokenKind, metadata, finalKeyword, typedBindingPattern, isModuleVar);
        }

        endContext();
        if (isModuleVar) {
            return STNodeFactory.createModuleVariableDeclarationNode(metadata, finalKeyword, typedBindingPattern,
                    assign, expr, semicolon);
        }
        return STNodeFactory.createVariableDeclarationNode(metadata, finalKeyword, typedBindingPattern, assign, expr,
                semicolon);
    }

    /**
     * <p>
     * Parse the RHS portion of the assignment.
     * </p>
     * <code>assignment-stmt-rhs := = action-or-expr ;</code>
     *
     * @param lvExpr LHS expression
     * @return Parsed node
     */
    private STNode parseAssignmentStmtRhs(STNode lvExpr) {
        STNode assign = parseAssignOp();
        STNode expr = parseActionOrExpression();
        STNode semicolon = parseSemicolon();
        endContext();

        if (lvExpr.kind == SyntaxKind.FUNCTION_CALL &&
                isPosibleFunctionalBindingPattern((STFunctionCallExpressionNode) lvExpr)) {
            lvExpr = getBindingPattern(lvExpr);
        }

        boolean lvExprValid = isValidLVExpr(lvExpr);
        if (!lvExprValid) {
            // Create a missing simple variable reference and attach the invalid lvExpr as minutiae
            STNode identifier = SyntaxErrors.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN);
            STNode simpleNameRef = STNodeFactory.createSimpleNameReferenceNode(identifier);
            lvExpr = SyntaxErrors.cloneWithLeadingInvalidNodeMinutiae(simpleNameRef, lvExpr,
                    DiagnosticErrorCode.ERROR_INVALID_EXPR_IN_ASSIGNMENT_LHS);
        }
        return STNodeFactory.createAssignmentStatementNode(lvExpr, assign, expr, semicolon);
    }

    /*
     * Expressions
     */

    /**
     * Parse expression. This will start parsing expressions from the lowest level of precedence.
     *
     * @return Parsed node
     */
    protected STNode parseExpression() {
        return parseExpression(DEFAULT_OP_PRECEDENCE, true, false);
    }

    /**
     * Parse action or expression. This will start parsing actions or expressions from the lowest level of precedence.
     *
     * @return Parsed node
     */
    private STNode parseActionOrExpression() {
        return parseExpression(DEFAULT_OP_PRECEDENCE, true, true);
    }

    private STNode parseActionOrExpressionInLhs(SyntaxKind tokenKind, STNode annots) {
        return parseExpression(tokenKind, DEFAULT_OP_PRECEDENCE, annots, false, true, false);
    }

    /**
     * Parse expression.
     *
     * @param isRhsExpr Flag indicating whether this is a rhs expression
     * @return Parsed node
     */
    private STNode parseExpression(boolean isRhsExpr) {
        return parseExpression(DEFAULT_OP_PRECEDENCE, isRhsExpr, false);
    }

    private boolean isValidLVExpr(STNode expression) {
        switch (expression.kind) {
            case SIMPLE_NAME_REFERENCE:
            case QUALIFIED_NAME_REFERENCE:
            case LIST_BINDING_PATTERN:
            case MAPPING_BINDING_PATTERN:
            case FUNCTIONAL_BINDING_PATTERN:
                return true;
            case FIELD_ACCESS:
                return isValidLVMemberExpr(((STFieldAccessExpressionNode) expression).expression);
            case INDEXED_EXPRESSION:
                return isValidLVMemberExpr(((STIndexedExpressionNode) expression).containerExpression);
            default:
                return (expression instanceof STMissingToken);
        }
    }

    private boolean isValidLVMemberExpr(STNode expression) {
        switch (expression.kind) {
            case SIMPLE_NAME_REFERENCE:
            case QUALIFIED_NAME_REFERENCE:
                return true;
            case FIELD_ACCESS:
                return isValidLVMemberExpr(((STFieldAccessExpressionNode) expression).expression);
            case INDEXED_EXPRESSION:
                return isValidLVMemberExpr(((STIndexedExpressionNode) expression).containerExpression);
            case BRACED_EXPRESSION:
                return isValidLVMemberExpr(((STBracedExpressionNode) expression).expression);
            default:
                return (expression instanceof STMissingToken);
        }
    }

    /**
     * Parse an expression that has an equal or higher precedence than a given level.
     *
     * @param precedenceLevel Precedence level of expression to be parsed
     * @param isRhsExpr Flag indicating whether this is a rhs expression
     * @param allowActions Flag indicating whether the current context support actions
     * @return Parsed node
     */
    private STNode parseExpression(OperatorPrecedence precedenceLevel, boolean isRhsExpr, boolean allowActions) {
        return parseExpression(precedenceLevel, isRhsExpr, allowActions, false);
    }

    private STNode parseExpression(OperatorPrecedence precedenceLevel, boolean isRhsExpr, boolean allowActions,
                                   boolean isInConditionalExpr) {
        STToken token = peek();
        return parseExpression(token.kind, precedenceLevel, isRhsExpr, allowActions, false, isInConditionalExpr);
    }

    private STNode parseExpression(SyntaxKind kind, OperatorPrecedence precedenceLevel, boolean isRhsExpr,
                                   boolean allowActions, boolean isInMatchGuard, boolean isInConditionalExpr) {
        STNode expr = parseTerminalExpression(kind, isRhsExpr, allowActions, isInConditionalExpr);
        return parseExpressionRhs(precedenceLevel, expr, isRhsExpr, allowActions, isInMatchGuard, isInConditionalExpr);
    }

    private STNode attachErrorExpectedActionFoundDiagnostic(STNode node) {
        return SyntaxErrors.addDiagnostic(node, DiagnosticErrorCode.ERROR_EXPRESSION_EXPECTED_ACTION_FOUND);
    }

    private STNode parseExpression(SyntaxKind kind, OperatorPrecedence precedenceLevel, STNode annots,
                                   boolean isRhsExpr, boolean allowActions, boolean isInConditionalExpr) {
        STNode expr = parseTerminalExpression(kind, annots, isRhsExpr, allowActions, isInConditionalExpr);
        return parseExpressionRhs(precedenceLevel, expr, isRhsExpr, allowActions, false, isInConditionalExpr);
    }

    /**
     * Parse terminal expressions. A terminal expression has the highest precedence level
     * out of all expressions, and will be at the leaves of an expression tree.
     *
     * @param annots Annotations
     * @param isRhsExpr Is a rhs expression
     * @param allowActions Allow actions
     * @return Parsed node
     */
    private STNode parseTerminalExpression(STNode annots, boolean isRhsExpr, boolean allowActions,
                                           boolean isInConditionalExpr) {
        return parseTerminalExpression(peek().kind, annots, isRhsExpr, allowActions, isInConditionalExpr);
    }

    private STNode parseTerminalExpression(SyntaxKind kind, boolean isRhsExpr, boolean allowActions,
                                           boolean isInConditionalExpr) {
        STNode annots;
        if (kind == SyntaxKind.AT_TOKEN) {
            annots = parseOptionalAnnotations();
            kind = peek().kind;
        } else {
            annots = STNodeFactory.createEmptyNodeList();
        }

        STNode expr = parseTerminalExpression(kind, annots, isRhsExpr, allowActions, isInConditionalExpr);
        if (!isNodeListEmpty(annots) && expr.kind != SyntaxKind.START_ACTION) {
            expr = SyntaxErrors.cloneWithLeadingInvalidNodeMinutiae(expr, annots,
                    DiagnosticErrorCode.ERROR_ANNOTATIONS_ATTACHED_TO_EXPRESSION);
        }

        return expr;
    }

    private STNode parseTerminalExpression(SyntaxKind kind, STNode annots, boolean isRhsExpr, boolean allowActions,
                                           boolean isInConditionalExpr) {
        // Whenever a new expression start is added, make sure to
        // add it to all the other places as well.
        switch (kind) {
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                return parseBasicLiteral();
            case IDENTIFIER_TOKEN:
                return parseQualifiedIdentifier(ParserRuleContext.VARIABLE_REF, isInConditionalExpr);
            case OPEN_PAREN_TOKEN:
                return parseBracedExpression(isRhsExpr, allowActions);
            case CHECK_KEYWORD:
            case CHECKPANIC_KEYWORD:
                // In the checking action, nested actions are allowed. And that's the only
                // place where actions are allowed within an action or an expression.
                return parseCheckExpression(isRhsExpr, allowActions, isInConditionalExpr);
            case FAIL_KEYWORD:
                return parseFailExpression(isRhsExpr, allowActions, isInConditionalExpr);
            case OPEN_BRACE_TOKEN:
                return parseMappingConstructorExpr();
            case TYPEOF_KEYWORD:
                return parseTypeofExpression(isRhsExpr, isInConditionalExpr);
            case PLUS_TOKEN:
            case MINUS_TOKEN:
            case NEGATION_TOKEN:
            case EXCLAMATION_MARK_TOKEN:
                return parseUnaryExpression(isRhsExpr, isInConditionalExpr);
            case TRAP_KEYWORD:
                return parseTrapExpression(isRhsExpr, allowActions, isInConditionalExpr);
            case OPEN_BRACKET_TOKEN:
                return parseListConstructorExpr();
            case LT_TOKEN:
                return parseTypeCastExpr(isRhsExpr, allowActions, isInConditionalExpr);
            case TABLE_KEYWORD:
            case STREAM_KEYWORD:
            case FROM_KEYWORD:
                return parseTableConstructorOrQuery(isRhsExpr);
            case ERROR_KEYWORD:
                return parseErrorConstructorExpr();
            case LET_KEYWORD:
                return parseLetExpression(isRhsExpr);
            case BACKTICK_TOKEN:
                return parseTemplateExpression();
            case XML_KEYWORD:
                STToken nextNextToken = getNextNextToken(kind);
                if (nextNextToken.kind == SyntaxKind.BACKTICK_TOKEN) {
                    return parseXMLTemplateExpression();
                }
                return parseSimpleTypeDescriptor();
            case STRING_KEYWORD:
                nextNextToken = getNextNextToken(kind);
                if (nextNextToken.kind == SyntaxKind.BACKTICK_TOKEN) {
                    return parseStringTemplateExpression();
                }
                return parseSimpleTypeDescriptor();
            case FUNCTION_KEYWORD:
                return parseExplicitFunctionExpression(annots, isRhsExpr);
            case AT_TOKEN:
                // Annon-func can have annotations. Check for other expressions
                // that can start with annots.
                break;
            case NEW_KEYWORD:
                return parseNewExpression();
            case START_KEYWORD:
                return parseStartAction(annots);
            case FLUSH_KEYWORD:
                return parseFlushAction();
            case LEFT_ARROW_TOKEN:
                return parseReceiveAction();
            case WAIT_KEYWORD:
                return parseWaitAction();
            case COMMIT_KEYWORD:
                return parseCommitAction();
            case TRANSACTIONAL_KEYWORD:
                return parseTransactionalExpression();
            case SERVICE_KEYWORD:
                return parseServiceConstructorExpression(annots);
            case BASE16_KEYWORD:
            case BASE64_KEYWORD:
                return parseByteArrayLiteral(kind);
            default:
                if (isSimpleType(kind)) {
                    return parseSimpleTypeDescriptor();
                }

                break;
        }

        Solution solution = recover(peek(), ParserRuleContext.TERMINAL_EXPRESSION, annots, isRhsExpr, allowActions,
                isInConditionalExpr);
        if (solution.action == Action.REMOVE) {
            return solution.recoveredNode;
        }

        if (solution.action == Action.KEEP) {
            if (kind == SyntaxKind.XML_KEYWORD) {
                return parseXMLTemplateExpression();
            }

            return parseStringTemplateExpression();
        }

        switch (solution.tokenKind) {
            case IDENTIFIER_TOKEN:
                return parseQualifiedIdentifier(solution.recoveredNode, isInConditionalExpr);
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                return solution.recoveredNode;
            default:
                return parseTerminalExpression(solution.tokenKind, annots, isRhsExpr, allowActions,
                        isInConditionalExpr);
        }
    }

    private boolean isValidExprStart(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
            case IDENTIFIER_TOKEN:
            case OPEN_PAREN_TOKEN:
            case CHECK_KEYWORD:
            case CHECKPANIC_KEYWORD:
            case FAIL_KEYWORD:
            case OPEN_BRACE_TOKEN:
            case TYPEOF_KEYWORD:
            case PLUS_TOKEN:
            case MINUS_TOKEN:
            case NEGATION_TOKEN:
            case EXCLAMATION_MARK_TOKEN:
            case TRAP_KEYWORD:
            case OPEN_BRACKET_TOKEN:
            case LT_TOKEN:
            case TABLE_KEYWORD:
            case STREAM_KEYWORD:
            case FROM_KEYWORD:
            case ERROR_KEYWORD:
            case LET_KEYWORD:
            case BACKTICK_TOKEN:
            case XML_KEYWORD:
            case STRING_KEYWORD:
            case FUNCTION_KEYWORD:
            case AT_TOKEN:
            case NEW_KEYWORD:
            case START_KEYWORD:
            case FLUSH_KEYWORD:
            case LEFT_ARROW_TOKEN:
            case WAIT_KEYWORD:
            case SERVICE_KEYWORD:
                return true;
            default:
                return isSimpleType(tokenKind);
        }
    }

    /**
     * <p>
     * Parse a new expression.
     * </p>
     * <code>
     *  new-expr := explicit-new-expr | implicit-new-expr
     *  <br/>
     *  explicit-new-expr := new type-descriptor ( arg-list )
     *  <br/>
     *  implicit-new-expr := new [( arg-list )]
     * </code>
     *
     * @return Parsed NewExpression node.
     */
    private STNode parseNewExpression() {
        STNode newKeyword = parseNewKeyword();
        return parseNewKeywordRhs(newKeyword);
    }

    /**
     * <p>
     * Parse `new` keyword.
     * </p>
     *
     * @return Parsed NEW_KEYWORD Token.
     */
    private STNode parseNewKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.NEW_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.NEW_KEYWORD);
            return sol.recoveredNode;
        }
    }

    private STNode parseNewKeywordRhs(STNode newKeyword) {
        STNode token = peek();
        return parseNewKeywordRhs(token.kind, newKeyword);
    }

    /**
     * <p>
     * Parse an implicit or explicit new expression.
     * </p>
     *
     * @param kind next token kind.
     * @param newKeyword parsed node for `new` keyword.
     * @return Parsed new-expression node.
     */
    private STNode parseNewKeywordRhs(SyntaxKind kind, STNode newKeyword) {
        switch (kind) {
            case OPEN_PAREN_TOKEN:
                return parseImplicitNewRhs(newKeyword);
            case SEMICOLON_TOKEN:
                break;
            case IDENTIFIER_TOKEN:
            case OBJECT_KEYWORD:
            case STREAM_KEYWORD:
                return parseTypeDescriptorInNewExpr(newKeyword);
            default:
                break;
        }

        return STNodeFactory.createImplicitNewExpressionNode(newKeyword, STNodeFactory.createEmptyNode());
    }

    /**
     * <p>
     * Parse an Explicit New expression.
     * </p>
     * <code>
     *  explicit-new-expr := new type-descriptor ( arg-list )
     * </code>
     *
     * @param newKeyword Parsed `new` keyword.
     * @return the Parsed Explicit New Expression.
     */
    private STNode parseTypeDescriptorInNewExpr(STNode newKeyword) {
        STNode typeDescriptor = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_NEW_EXPR);
        STNode parenthesizedArgsList = parseParenthesizedArgList();

        return STNodeFactory.createExplicitNewExpressionNode(newKeyword, typeDescriptor, parenthesizedArgsList);
    }

    /**
     * <p>
     * Parse an <code>implicit-new-expr</code> with arguments.
     * </p>
     *
     * @param newKeyword Parsed `new` keyword.
     * @return Parsed implicit-new-expr.
     */
    private STNode parseImplicitNewRhs(STNode newKeyword) {
        STNode implicitNewArgList = parseParenthesizedArgList();
        return STNodeFactory.createImplicitNewExpressionNode(newKeyword, implicitNewArgList);
    }

    /**
     * <p>
     * Parse the parenthesized argument list for a <code>new-expr</code>.
     * </p>
     *
     * @return Parsed parenthesized rhs of <code>new-expr</code>.
     */
    private STNode parseParenthesizedArgList() {
        STNode openParan = parseOpenParenthesis(ParserRuleContext.ARG_LIST_START);
        STNode arguments = parseArgsList();
        STNode closeParan = parseCloseParenthesis();

        return STNodeFactory.createParenthesizedArgList(openParan, arguments, closeParan);
    }

    /**
     * <p>
     * Parse the right-hand-side of an expression.
     * </p>
     * <code>expr-rhs := (binary-op expression
     *                              | dot identifier
     *                              | open-bracket expression close-bracket
     *                          )*</code>
     *
     * @param precedenceLevel Precedence level of the expression that is being parsed currently
     * @param lhsExpr LHS expression of the expression
     * @param isRhsExpr Flag indicating whether this is on a rhsExpr of a statement
     * @param allowActions Flag indicating whether the current context support actions
     * @return Parsed node
     */
    private STNode parseExpressionRhs(OperatorPrecedence precedenceLevel, STNode lhsExpr, boolean isRhsExpr,
                                      boolean allowActions) {
        return parseExpressionRhs(precedenceLevel, lhsExpr, isRhsExpr, allowActions, false, false);
    }

    private STNode parseExpressionRhs(OperatorPrecedence precedenceLevel, STNode lhsExpr, boolean isRhsExpr,
                                      boolean allowActions, boolean isInMatchGuard, boolean isInConditionalExpr) {
        STToken token = peek();
        return parseExpressionRhs(token.kind, precedenceLevel, lhsExpr, isRhsExpr, allowActions, isInMatchGuard,
                isInConditionalExpr);
    }

    /**
     * Parse the right hand side of an expression given the next token kind.
     *
     * @param tokenKind Next token kind
     * @param currentPrecedenceLevel Precedence level of the expression that is being parsed currently
     * @param lhsExpr LHS expression
     * @param isRhsExpr Flag indicating whether this is a rhs expr or not
     * @param allowActions Flag indicating whether to allow actions or not
     * @param isInMatchGuard Flag indicating whether this expression is in a match-guard
     * @return Parsed node
     */
    private STNode parseExpressionRhs(SyntaxKind tokenKind, OperatorPrecedence currentPrecedenceLevel, STNode lhsExpr,
                                      boolean isRhsExpr, boolean allowActions, boolean isInMatchGuard,
                                      boolean isInConditionalExpr) {
        STNode actionOrExpression = parseExpressionRhsInternal(tokenKind, currentPrecedenceLevel, lhsExpr, isRhsExpr,
                allowActions, isInMatchGuard, isInConditionalExpr);
        // braced actions are just paranthesis enclosing actions, no need to add a diagnostic there when we have added
        // diagnostics to its children
        if (!allowActions && isAction(actionOrExpression) && actionOrExpression.kind != SyntaxKind.BRACED_ACTION) {
            actionOrExpression = attachErrorExpectedActionFoundDiagnostic(actionOrExpression);
        }
        return actionOrExpression;
    }

    private STNode parseExpressionRhsInternal(SyntaxKind tokenKind, OperatorPrecedence currentPrecedenceLevel,
                                              STNode lhsExpr, boolean isRhsExpr, boolean allowActions,
                                              boolean isInMatchGuard, boolean isInConditionalExpr) {
        if (isEndOfExpression(tokenKind, isRhsExpr, isInMatchGuard, lhsExpr.kind)) {
            return lhsExpr;
        }

        if (lhsExpr.kind == SyntaxKind.ASYNC_SEND_ACTION) {
            // Async-send action can only exists in an action-statement. It also has to be the
            // right-most action. i.e: Should be followed by a semicolon
            return lhsExpr;
        }

        if (!isValidExprRhsStart(tokenKind, lhsExpr.kind)) {
            return recoverExpressionRhs(currentPrecedenceLevel, lhsExpr, isRhsExpr, allowActions, isInMatchGuard,
                    isInConditionalExpr);
        }

        // Look for >> and >>> tokens as they are not sent from lexer due to ambiguity. e.g. <map<int>> a
        if (tokenKind == SyntaxKind.GT_TOKEN && peek(2).kind == SyntaxKind.GT_TOKEN) {
            if (peek(3).kind == SyntaxKind.GT_TOKEN) {
                tokenKind = SyntaxKind.TRIPPLE_GT_TOKEN;
            } else {
                tokenKind = SyntaxKind.DOUBLE_GT_TOKEN;
            }
        }

        // If the precedence level of the operator that was being parsed is higher than the newly found (next)
        // operator, then return and finish the previous expr, because it has a higher precedence.
        OperatorPrecedence nextOperatorPrecedence = getOpPrecedence(tokenKind);
        if (currentPrecedenceLevel.isHigherThanOrEqual(nextOperatorPrecedence, allowActions)) {
            return lhsExpr;
        }

        STNode newLhsExpr;
        STNode operator;
        switch (tokenKind) {
            case OPEN_PAREN_TOKEN:
                newLhsExpr = parseFuncCall(lhsExpr);
                break;
            case OPEN_BRACKET_TOKEN:
                newLhsExpr = parseMemberAccessExpr(lhsExpr, isRhsExpr);
                break;
            case DOT_TOKEN:
                newLhsExpr = parseFieldAccessOrMethodCall(lhsExpr, isInConditionalExpr);
                break;
            case IS_KEYWORD:
                newLhsExpr = parseTypeTestExpression(lhsExpr, isInConditionalExpr);
                break;
            case RIGHT_ARROW_TOKEN:
                newLhsExpr = parseRemoteMethodCallOrAsyncSendAction(lhsExpr, isRhsExpr);
                break;
            case SYNC_SEND_TOKEN:
                newLhsExpr = parseSyncSendAction(lhsExpr);
                break;
            case RIGHT_DOUBLE_ARROW_TOKEN:
                newLhsExpr = parseImplicitAnonFunc(lhsExpr, isRhsExpr);
                break;
            case ANNOT_CHAINING_TOKEN:
                newLhsExpr = parseAnnotAccessExpression(lhsExpr, isInConditionalExpr);
                break;
            case OPTIONAL_CHAINING_TOKEN:
                newLhsExpr = parseOptionalFieldAccessExpression(lhsExpr, isInConditionalExpr);
                break;
            case QUESTION_MARK_TOKEN:
                newLhsExpr = parseConditionalExpression(lhsExpr);
                break;
            case DOT_LT_TOKEN:
                newLhsExpr = parseXMLFilterExpression(lhsExpr);
                break;
            case SLASH_LT_TOKEN:
            case DOUBLE_SLASH_DOUBLE_ASTERISK_LT_TOKEN:
            case SLASH_ASTERISK_TOKEN:
                newLhsExpr = parseXMLStepExpression(lhsExpr);
                break;
            default:
                // Handle 'a/<b|c>...' scenarios. These have ambiguity between being a xml-step expr
                // or a binary expr (division), with a type-cast as the denominator.
                if (tokenKind == SyntaxKind.SLASH_TOKEN && peek(2).kind == SyntaxKind.LT_TOKEN) {
                    SyntaxKind expectedNodeType = getExpectedNodeKind(3, isRhsExpr, isInMatchGuard, lhsExpr.kind);
                    if (expectedNodeType == SyntaxKind.XML_STEP_EXPRESSION) {
                        newLhsExpr = createXMLStepExpression(lhsExpr);
                        break;
                    }

                    // if (expectedNodeType == SyntaxKind.TYPE_CAST_EXPRESSION) or anything else.
                    // Fall through, and continue to parse as a binary expr
                }

                if (tokenKind == SyntaxKind.DOUBLE_GT_TOKEN) {
                    operator = parseSignedRightShiftToken();
                } else if (tokenKind == SyntaxKind.TRIPPLE_GT_TOKEN) {
                    operator = parseUnsignedRightShiftToken();
                } else {
                    operator = parseBinaryOperator();
                }

                // Treat everything else as binary expression.

                // Parse the expression that follows the binary operator, until a operator with different precedence
                // is encountered. If an operator with a lower precedence is reached, then come back here and finish
                // the current binary expr. If a an operator with higher precedence level is reached, then complete
                // that binary-expr, come back here and finish the current expr.

                // Actions within binary-expressions are not allowed.
                if (isAction(lhsExpr) && lhsExpr.kind != SyntaxKind.BRACED_ACTION) {
                    lhsExpr = attachErrorExpectedActionFoundDiagnostic(lhsExpr);
                }
                STNode rhsExpr = parseExpression(nextOperatorPrecedence, isRhsExpr, false, isInConditionalExpr);
                newLhsExpr = STNodeFactory.createBinaryExpressionNode(SyntaxKind.BINARY_EXPRESSION, lhsExpr, operator,
                        rhsExpr);
                break;
        }

        // Then continue the operators with the same precedence level.
        return parseExpressionRhsInternal(peek().kind, currentPrecedenceLevel, newLhsExpr, isRhsExpr, allowActions,
                isInMatchGuard, isInConditionalExpr);
    }

    private STNode recoverExpressionRhs(OperatorPrecedence currentPrecedenceLevel, STNode lhsExpr, boolean isRhsExpr,
                                        boolean allowActions, boolean isInMatchGuard, boolean isInConditionalExpr) {
        STToken token = peek();
        Solution solution = recover(token, ParserRuleContext.EXPRESSION_RHS, currentPrecedenceLevel, lhsExpr, isRhsExpr,
                allowActions, isInMatchGuard, isInConditionalExpr);

        // If the current rule was recovered by removing a token, then this entire rule is already
        // parsed while recovering. so we done need to parse the remaining of this rule again.
        // Proceed only if the recovery action was an insertion.
        if (solution.action == Action.REMOVE) {
            return solution.recoveredNode;
        }

        // If the parser recovered by inserting a token, then try to re-parse the same rule with the
        // inserted token. This is done to pick the correct branch to continue the parsing.
        if (solution.ctx == ParserRuleContext.BINARY_OPERATOR) {
            // We come here if the operator is missing. Treat this as injecting an operator
            // that matches to the current operator precedence level, and continue.
            SyntaxKind binaryOpKind = getBinaryOperatorKindToInsert(currentPrecedenceLevel);
            return parseExpressionRhsInternal(binaryOpKind, currentPrecedenceLevel, lhsExpr, isRhsExpr, allowActions,
                    isInMatchGuard, isInConditionalExpr);
        } else {
            return parseExpressionRhsInternal(solution.tokenKind, currentPrecedenceLevel, lhsExpr, isRhsExpr,
                    allowActions, isInMatchGuard, isInConditionalExpr);
        }
    }

    private STNode createXMLStepExpression(STNode lhsExpr) {
        STNode newLhsExpr;
        STNode slashToken = parseSlashToken();
        STNode ltToken = parseLTToken();

        STNode slashLT;
        if (hasTrailingMinutiae(slashToken) || hasLeadingMinutiae(ltToken)) {
            List<STNodeDiagnostic> diagnostics = new ArrayList<>();
            diagnostics
                    .add(SyntaxErrors.createDiagnostic(DiagnosticErrorCode.ERROR_INVALID_WHITESPACE_IN_SLASH_LT_TOKEN));
            slashLT = STNodeFactory.createMissingToken(SyntaxKind.SLASH_LT_TOKEN, diagnostics);
            slashLT = SyntaxErrors.cloneWithLeadingInvalidNodeMinutiae(slashLT, slashToken);
            slashLT = SyntaxErrors.cloneWithLeadingInvalidNodeMinutiae(slashLT, ltToken);
        } else {
            slashLT = STNodeFactory.createToken(SyntaxKind.SLASH_LT_TOKEN, slashToken.leadingMinutiae(),
                    ltToken.trailingMinutiae());
        }

        STNode namePattern = parseXMLNamePatternChain(slashLT);
        newLhsExpr = STNodeFactory.createXMLStepExpressionNode(lhsExpr, namePattern);
        return newLhsExpr;
    }

    private SyntaxKind getExpectedNodeKind(int lookahead, boolean isRhsExpr, boolean isInMatchGuard,
                                           SyntaxKind precedingNodeKind) {
        STToken nextToken = peek(lookahead);
        switch (nextToken.kind) {
            case ASTERISK_TOKEN:
                return SyntaxKind.XML_STEP_EXPRESSION;
            case GT_TOKEN:
                break;
            case PIPE_TOKEN:
                return getExpectedNodeKind(++lookahead, isRhsExpr, isInMatchGuard, precedingNodeKind);
            case IDENTIFIER_TOKEN:
                nextToken = peek(++lookahead);
                switch (nextToken.kind) {
                    case GT_TOKEN: // a>
                        break;
                    case PIPE_TOKEN: // a|
                        return getExpectedNodeKind(++lookahead, isRhsExpr, isInMatchGuard, precedingNodeKind);
                    case COLON_TOKEN:
                        nextToken = peek(++lookahead);
                        switch (nextToken.kind) {
                            case ASTERISK_TOKEN: // a:*
                            case GT_TOKEN: // a:>
                                return SyntaxKind.XML_STEP_EXPRESSION;
                            case IDENTIFIER_TOKEN: // a:b
                                nextToken = peek(++lookahead);

                                // a:b |
                                if (nextToken.kind == SyntaxKind.PIPE_TOKEN) {
                                    return getExpectedNodeKind(++lookahead, isRhsExpr, isInMatchGuard,
                                            precedingNodeKind);
                                }

                                // a:b> or everything else
                                break;
                            default:
                                return SyntaxKind.TYPE_CAST_EXPRESSION;
                        }
                        break;
                    default:
                        return SyntaxKind.TYPE_CAST_EXPRESSION;
                }
                break;
            default:
                return SyntaxKind.TYPE_CAST_EXPRESSION;
        }

        nextToken = peek(++lookahead);
        switch (nextToken.kind) {
            case OPEN_BRACKET_TOKEN:
            case OPEN_BRACE_TOKEN:
            case PLUS_TOKEN:
            case MINUS_TOKEN:
            case FROM_KEYWORD:
            case LET_KEYWORD:
                return SyntaxKind.XML_STEP_EXPRESSION;
            default:
                if (isValidExpressionStart(nextToken.kind, lookahead)) {
                    break;
                }
                return SyntaxKind.XML_STEP_EXPRESSION;
        }

        return SyntaxKind.TYPE_CAST_EXPRESSION;
    }

    private boolean hasTrailingMinutiae(STNode node) {
        return node.widthWithTrailingMinutiae() > node.width();
    }

    private boolean hasLeadingMinutiae(STNode node) {
        return node.widthWithLeadingMinutiae() > node.width();
    }

    private boolean isValidExprRhsStart(SyntaxKind tokenKind, SyntaxKind precedingNodeKind) {
        switch (tokenKind) {
            case OPEN_PAREN_TOKEN:
                // Only an identifier or a qualified identifier is followed by a function call.
                return precedingNodeKind == SyntaxKind.QUALIFIED_NAME_REFERENCE ||
                        precedingNodeKind == SyntaxKind.SIMPLE_NAME_REFERENCE;
            case DOT_TOKEN:
            case OPEN_BRACKET_TOKEN:
            case IS_KEYWORD:
            case RIGHT_ARROW_TOKEN:
            case RIGHT_DOUBLE_ARROW_TOKEN:
            case SYNC_SEND_TOKEN:
            case ANNOT_CHAINING_TOKEN:
            case OPTIONAL_CHAINING_TOKEN:
            case QUESTION_MARK_TOKEN:
            case COLON_TOKEN:
            case DOT_LT_TOKEN:
            case SLASH_LT_TOKEN:
            case DOUBLE_SLASH_DOUBLE_ASTERISK_LT_TOKEN:
            case SLASH_ASTERISK_TOKEN:
                return true;
            default:
                return isBinaryOperator(tokenKind);
        }
    }

    /**
     * Parse member access expression.
     *
     * @param lhsExpr Container expression
     * @param isRhsExpr Is this is a rhs expression
     * @return Member access expression
     */
    private STNode parseMemberAccessExpr(STNode lhsExpr, boolean isRhsExpr) {
        startContext(ParserRuleContext.MEMBER_ACCESS_KEY_EXPR);
        STNode openBracket = parseOpenBracket();
        STNode keyExpr = parseMemberAccessKeyExprs(isRhsExpr);
        STNode closeBracket = parseCloseBracket();
        endContext();

        // If this is in RHS, then its definitely a member-access.
        if (isRhsExpr && ((STNodeList) keyExpr).isEmpty()) {
            STNode missingVarRef = STNodeFactory
                    .createSimpleNameReferenceNode(SyntaxErrors.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN));
            keyExpr = STNodeFactory.createNodeList(missingVarRef);
            closeBracket = SyntaxErrors.addDiagnostic(closeBracket,
                    DiagnosticErrorCode.ERROR_MISSING_KEY_EXPR_IN_MEMBER_ACCESS_EXPR);
        }
        return STNodeFactory.createIndexedExpressionNode(lhsExpr, openBracket, keyExpr, closeBracket);
    }

    /**
     * Parse key expression of a member access expression. A type descriptor
     * that starts with a type-ref (e.g: T[a][b]) also goes through this
     * method.
     * <p>
     * <code>key-expression := single-key-expression | multi-key-expression</code>
     *
     * @param isRhsExpr Is this is a rhs expression
     * @return Key expression
     */
    private STNode parseMemberAccessKeyExprs(boolean isRhsExpr) {
        List<STNode> exprList = new ArrayList<>();

        // Parse the remaining exprs
        STNode keyExpr;
        STNode keyExprEnd;
        while (!isEndOfTypeList(peek().kind)) {
            keyExpr = parseKeyExpr(isRhsExpr);
            exprList.add(keyExpr);
            keyExprEnd = parseMemberAccessKeyExprEnd();
            if (keyExprEnd == null) {
                break;
            }
            exprList.add(keyExprEnd);
        }

        return STNodeFactory.createNodeList(exprList);
    }

    private STNode parseKeyExpr(boolean isRhsExpr) {
        if (!isRhsExpr && peek().kind == SyntaxKind.ASTERISK_TOKEN) {
            return STNodeFactory.createBasicLiteralNode(SyntaxKind.ASTERISK_TOKEN, consume());
        }

        return parseExpression(isRhsExpr);
    }

    private STNode parseMemberAccessKeyExprEnd() {
        return parseMemberAccessKeyExprEnd(peek().kind);
    }

    private STNode parseMemberAccessKeyExprEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACKET_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.MEMBER_ACCESS_KEY_EXPR_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseMemberAccessKeyExprEnd(solution.tokenKind);
        }
    }

    /**
     * Parse close bracket.
     *
     * @return Parsed node
     */
    private STNode parseCloseBracket() {
        STToken token = peek();
        if (token.kind == SyntaxKind.CLOSE_BRACKET_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CLOSE_BRACKET);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse field access, xml required attribute access expressions or method call expression.
     * <p>
     * <code>
     * field-access-expr := expression . field-name
     * <br/>
     * xml-required-attribute-access-expr := expression . xml-attribute-name
     * <br/>
     * xml-attribute-name := xml-qualified-name | qualified-identifier | identifier
     * <br/>
     * method-call-expr := expression . method-name ( arg-list )
     * </code>
     *
     * @param lhsExpr Preceding expression of the field access or method call
     * @return One of <code>field-access-expression</code> or <code>method-call-expression</code>.
     */
    private STNode parseFieldAccessOrMethodCall(STNode lhsExpr, boolean isInConditionalExpr) {
        STNode dotToken = parseDotToken();
        STToken token = peek();
        if (token.kind == SyntaxKind.MAP_KEYWORD || token.kind == SyntaxKind.START_KEYWORD) {
            STNode methodName = getKeywordAsSimpleNameRef();
            STNode openParen = parseOpenParenthesis(ParserRuleContext.ARG_LIST_START);
            STNode args = parseArgsList();
            STNode closeParen = parseCloseParenthesis();
            return STNodeFactory.createMethodCallExpressionNode(lhsExpr, dotToken, methodName, openParen, args,
                    closeParen);
        }

        STNode fieldOrMethodName = parseFieldAccessIdentifier(isInConditionalExpr);
        if (fieldOrMethodName.kind == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
            return STNodeFactory.createFieldAccessExpressionNode(lhsExpr, dotToken, fieldOrMethodName);
        }

        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.OPEN_PAREN_TOKEN) {
            // function invocation
            STNode openParen = parseOpenParenthesis(ParserRuleContext.ARG_LIST_START);
            STNode args = parseArgsList();
            STNode closeParen = parseCloseParenthesis();
            return STNodeFactory.createMethodCallExpressionNode(lhsExpr, dotToken, fieldOrMethodName, openParen, args,
                    closeParen);
        }

        // Everything else is field-access
        return STNodeFactory.createFieldAccessExpressionNode(lhsExpr, dotToken, fieldOrMethodName);
    }

    private STNode getKeywordAsSimpleNameRef() {
        STToken mapKeyword = consume();
        STNode methodName = STNodeFactory.createIdentifierToken(mapKeyword.text(), mapKeyword.leadingMinutiae(),
                mapKeyword.trailingMinutiae(), mapKeyword.diagnostics());
        methodName = STNodeFactory.createSimpleNameReferenceNode(methodName);
        return methodName;
    }

    /**
     * <p>
     * Parse braced expression.
     * </p>
     * <code>braced-expr := ( expression )</code>
     *
     * @param isRhsExpr Flag indicating whether this is on a rhsExpr of a statement
     * @param allowActions Allow actions
     * @return Parsed node
     */
    private STNode parseBracedExpression(boolean isRhsExpr, boolean allowActions) {
        STNode openParen = parseOpenParenthesis(ParserRuleContext.OPEN_PARENTHESIS);

        if (peek().kind == SyntaxKind.CLOSE_PAREN_TOKEN) {
            // Could be nill literal or empty param-list of an implicit-anon-func-expr'
            return parseNilLiteralOrEmptyAnonFuncParamRhs(openParen);
        }

        startContext(ParserRuleContext.BRACED_EXPR_OR_ANON_FUNC_PARAMS);
        STNode expr;
        if (allowActions) {
            expr = parseExpression(DEFAULT_OP_PRECEDENCE, isRhsExpr, true);
        } else {
            expr = parseExpression(isRhsExpr);
        }

        return parseBracedExprOrAnonFuncParamRhs(peek().kind, openParen, expr, isRhsExpr);
    }

    private STNode parseNilLiteralOrEmptyAnonFuncParamRhs(STNode openParen) {
        STNode closeParen = parseCloseParenthesis();
        STToken nextToken = peek();
        if (nextToken.kind != SyntaxKind.RIGHT_DOUBLE_ARROW_TOKEN) {
            return STNodeFactory.createNilLiteralNode(openParen, closeParen);
        } else {
            STNode params = STNodeFactory.createNodeList();
            STNode anonFuncParam =
                    STNodeFactory.createImplicitAnonymousFunctionParameters(openParen, params, closeParen);
            return anonFuncParam;
        }
    }

    private STNode parseBracedExprOrAnonFuncParamRhs(STNode openParen, STNode expr, boolean isRhsExpr) {
        STToken nextToken = peek();
        return parseBracedExprOrAnonFuncParamRhs(nextToken.kind, openParen, expr, isRhsExpr);
    }

    private STNode parseBracedExprOrAnonFuncParamRhs(SyntaxKind nextTokenKind, STNode openParen, STNode expr,
                                                     boolean isRhsExpr) {
        if (expr.kind == SyntaxKind.SIMPLE_NAME_REFERENCE) {
            switch (nextTokenKind) {
                case CLOSE_PAREN_TOKEN:
                    break;
                case COMMA_TOKEN:
                    // Here the context is ended inside the method.
                    return parseImplicitAnonFunc(openParen, expr, isRhsExpr);
                default:
                    STToken token = peek();
                    Solution solution = recover(token, ParserRuleContext.BRACED_EXPR_OR_ANON_FUNC_PARAM_RHS, openParen,
                            expr, isRhsExpr);

                    // If the parser recovered by inserting a token, then try to re-parse the same
                    // rule with the inserted token. This is done to pick the correct branch
                    // to continue the parsing.
                    if (solution.action == Action.REMOVE) {
                        endContext();
                        return solution.recoveredNode;
                    }

                    return parseBracedExprOrAnonFuncParamRhs(solution.tokenKind, openParen, expr, isRhsExpr);
            }
        }

        STNode closeParen = parseCloseParenthesis();
        endContext();
        if (isAction(expr)) {
            return STNodeFactory.createBracedExpressionNode(SyntaxKind.BRACED_ACTION, openParen, expr, closeParen);
        }
        return STNodeFactory.createBracedExpressionNode(SyntaxKind.BRACED_EXPRESSION, openParen, expr, closeParen);
    }

    /**
     * Check whether a given node is an action node.
     *
     * @param node Node to check
     * @return <code>true</code> if the node is an action node. <code>false</code> otherwise
     */
    private boolean isAction(STNode node) {
        switch (node.kind) {
            case REMOTE_METHOD_CALL_ACTION:
            case BRACED_ACTION:
            case CHECK_ACTION:
            case START_ACTION:
            case TRAP_ACTION:
            case FLUSH_ACTION:
            case ASYNC_SEND_ACTION:
            case SYNC_SEND_ACTION:
            case RECEIVE_ACTION:
            case WAIT_ACTION:
            case QUERY_ACTION:
            case COMMIT_ACTION:
            case FAIL_ACTION:
                return true;
            default:
                return false;
        }
    }

    /**
     * Check whether the given token is an end of a expression.
     *
     * @param tokenKind Token to check
     * @param isRhsExpr Flag indicating whether this is on a rhsExpr of a statement
     * @return <code>true</code> if the token represents an end of a block. <code>false</code> otherwise
     */
    private boolean isEndOfExpression(SyntaxKind tokenKind, boolean isRhsExpr, boolean isInMatchGuard,
                                      SyntaxKind precedingNodeKind) {
        if (!isRhsExpr) {
            if (isCompoundBinaryOperator(tokenKind)) {
                return true;
            }

            if (isInMatchGuard && tokenKind == SyntaxKind.RIGHT_DOUBLE_ARROW_TOKEN) {
                return true;
            }

            return !isValidExprRhsStart(tokenKind, precedingNodeKind);
        }

        switch (tokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
            case OPEN_BRACE_TOKEN:
            case CLOSE_PAREN_TOKEN:
            case CLOSE_BRACKET_TOKEN:
            case SEMICOLON_TOKEN:
            case COMMA_TOKEN:
            case PUBLIC_KEYWORD:
            case CONST_KEYWORD:
            case LISTENER_KEYWORD:
            case EQUAL_TOKEN:
            case DOCUMENTATION_STRING:
            case AT_TOKEN:
            case AS_KEYWORD:
            case IN_KEYWORD:
            case FROM_KEYWORD:
            case WHERE_KEYWORD:
            case LET_KEYWORD:
            case SELECT_KEYWORD:
            case DO_KEYWORD:
            case COLON_TOKEN:
            case ON_KEYWORD:
            case CONFLICT_KEYWORD:
            case LIMIT_KEYWORD:
            case JOIN_KEYWORD:
            case OUTER_KEYWORD:
            case ORDER_KEYWORD:
            case BY_KEYWORD:
            case ASCENDING_KEYWORD:
            case DESCENDING_KEYWORD:
                return true;
            case RIGHT_DOUBLE_ARROW_TOKEN:
                return isInMatchGuard;
            default:
                return isSimpleType(tokenKind);
        }
    }

    /**
     * Parse basic literals. It is assumed that we come here after validation.
     *
     * @return Parsed node
     */
    private STNode parseBasicLiteral() {
        STToken literalToken = consume();
        return STNodeFactory.createBasicLiteralNode(literalToken.kind, literalToken);
    }

    /**
     * Parse function call expression.
     * <code>function-call-expr := function-reference ( arg-list )
     * function-reference := variable-reference</code>
     *
     * @param identifier Function name
     * @return Function call expression
     */
    private STNode parseFuncCall(STNode identifier) {
        STNode openParen = parseOpenParenthesis(ParserRuleContext.ARG_LIST_START);
        STNode args = parseArgsList();
        STNode closeParen = parseCloseParenthesis();
        return STNodeFactory.createFunctionCallExpressionNode(identifier, openParen, args, closeParen);
    }

    /**
     * <p>
     * Parse error constructor expression.
     * </p>
     * <code>
     * error-constructor-expr := error ( arg-list )
     * </code>
     *
     * @return Error constructor expression
     */
    private STNode parseErrorConstructorExpr() {
        STNode errorKeyword = parseErrorKeyword();
        errorKeyword = createBuiltinSimpleNameReference(errorKeyword);
        return parseFuncCall(errorKeyword);
    }

    /**
     * Parse function call argument list.
     *
     * @return Parsed args list
     */
    private STNode parseArgsList() {
        startContext(ParserRuleContext.ARG_LIST);
        STToken token = peek();
        if (isEndOfParametersList(token.kind)) {
            STNode args = STNodeFactory.createEmptyNodeList();
            endContext();
            return args;
        }

        STNode firstArg = parseArgument();
        STNode argsList = parseArgList(firstArg);
        endContext();
        return argsList;
    }

    /**
     * Parse follow up arguments.
     *
     * @param firstArg first argument in the list
     * @return the argument list
     */
    private STNode parseArgList(STNode firstArg) {
        ArrayList<STNode> argsList = new ArrayList<>();
        argsList.add(firstArg);
        SyntaxKind lastValidArgKind = firstArg.kind;

        STToken nextToken = peek();
        while (!isEndOfParametersList(nextToken.kind)) {
            STNode argEnd = parseArgEnd(nextToken.kind);
            if (argEnd == null) {
                // null marks the end of args
                break;
            }

            nextToken = peek();
            STNode curArg = parseArgument(nextToken.kind);
            DiagnosticErrorCode errorCode = validateArgumentOrder(lastValidArgKind, curArg.kind);
            if (errorCode == null) {
                argsList.add(argEnd);
                argsList.add(curArg);
                lastValidArgKind = curArg.kind;
            } else if (errorCode == DiagnosticErrorCode.ERROR_NAMED_ARG_FOLLOWED_BY_POSITIONAL_ARG &&
                    isMissingPositionalArg(curArg)) {
                argsList.add(argEnd);
                argsList.add(curArg);
            } else {
                updateLastNodeInListWithInvalidNode(argsList, argEnd, null);
                updateLastNodeInListWithInvalidNode(argsList, curArg, errorCode);
            }

            nextToken = peek();
        }
        return STNodeFactory.createNodeList(argsList);
    }

    private DiagnosticErrorCode validateArgumentOrder(SyntaxKind prevArgKind, SyntaxKind curArgKind) {
        DiagnosticErrorCode errorCode = null;
        switch (prevArgKind) {
            case POSITIONAL_ARG:
                break;
            case NAMED_ARG:
                if (curArgKind == SyntaxKind.POSITIONAL_ARG) {
                    errorCode = DiagnosticErrorCode.ERROR_NAMED_ARG_FOLLOWED_BY_POSITIONAL_ARG;
                }
                break;
            case REST_ARG:
                // Nothing is allowed after a rest arg
                errorCode = DiagnosticErrorCode.ERROR_ARG_FOLLOWED_BY_REST_ARG;
                break;
            default:
                // This line should never get reached
                throw new IllegalStateException("Invalid SyntaxKind in an argument");
        }
        return errorCode;
    }

    private boolean isMissingPositionalArg(STNode arg) {
        STNode expr = ((STPositionalArgumentNode) arg).expression;
        return expr.kind == SyntaxKind.SIMPLE_NAME_REFERENCE && ((STSimpleNameReferenceNode) expr).name.isMissing();
    }

    private STNode parseArgEnd() {
        return parseArgEnd(peek().kind);
    }

    private STNode parseArgEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_PAREN_TOKEN:
                // null marks the end of args
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.ARG_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseArgEnd(solution.tokenKind);
        }
    }

    /**
     * Parse function call argument.
     *
     * @return Parsed argument node
     */
    private STNode parseArgument() {
        STToken token = peek();
        return parseArgument(token.kind);
    }

    private STNode parseArgument(SyntaxKind kind) {
        STNode arg;
        switch (kind) {
            case ELLIPSIS_TOKEN:
                STToken ellipsis = consume();
                STNode expr = parseExpression();
                arg = STNodeFactory.createRestArgumentNode(ellipsis, expr);
                break;
            case IDENTIFIER_TOKEN:
                // Identifier can means two things: either its a named-arg, or just an expression.
                // TODO: Handle package-qualified var-refs (i.e: qualified-identifier).
                arg = parseNamedOrPositionalArg(kind);
                break;
            default:
                if (isValidExprStart(kind)) {
                    expr = parseExpression();
                    arg = STNodeFactory.createPositionalArgumentNode(expr);
                    break;
                }

                Solution solution = recover(peek(), ParserRuleContext.ARG_START);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseArgument(solution.tokenKind);
        }

        return arg;
    }

    /**
     * Parse positional or named arg. This method assumed peek()/peek(1)
     * is always an identifier.
     *
     * @return Parsed argument node
     */
    private STNode parseNamedOrPositionalArg(SyntaxKind nextTokenKind) {
        STNode argNameOrExpr = parseTerminalExpression(peek().kind, true, false, false);
        STToken secondToken = peek();
        switch (secondToken.kind) {
            case EQUAL_TOKEN:
                STNode equal = parseAssignOp();
                STNode valExpr = parseExpression();
                return STNodeFactory.createNamedArgumentNode(argNameOrExpr, equal, valExpr);
            case COMMA_TOKEN:
            case CLOSE_PAREN_TOKEN:
                return STNodeFactory.createPositionalArgumentNode(argNameOrExpr);

            // Treat everything else as a single expression. If something is missing,
            // expression-parsing will recover it.
            default:
                argNameOrExpr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, argNameOrExpr, true, false);
                return STNodeFactory.createPositionalArgumentNode(argNameOrExpr);
        }
    }

    /**
     * Parse object type descriptor.
     *
     * @return Parsed node
     */
    private STNode parseObjectTypeDescriptor() {
        startContext(ParserRuleContext.OBJECT_TYPE_DESCRIPTOR);
        STNode objectTypeQualifiers = parseObjectTypeQualifiers();
        STNode objectKeyword = parseObjectKeyword();
        STNode openBrace = parseOpenBrace();
        STNode objectMembers = parseObjectMembers();
        STNode closeBrace = parseCloseBrace();
        endContext();
        return STNodeFactory.createObjectTypeDescriptorNode(objectTypeQualifiers, objectKeyword, openBrace,
                objectMembers, closeBrace);
    }

    /**
     * Parse object type qualifiers.
     *
     * @return Parsed node
     */
    private STNode parseObjectTypeQualifiers() {
        STToken nextToken = peek();
        return parseObjectTypeQualifiers(nextToken.kind);
    }

    private STNode parseObjectTypeQualifiers(SyntaxKind kind) {
        STNode firstQualifier;
        switch (kind) {
            case CLIENT_KEYWORD:
                firstQualifier = parseClientKeyword();
                break;
            case ABSTRACT_KEYWORD:
                firstQualifier = parseAbstractKeyword();
                break;
            case READONLY_KEYWORD:
                firstQualifier = parseReadonlyKeyword();
                break;
            case OBJECT_KEYWORD:
                return STNodeFactory.createEmptyNodeList();
            default:
                Solution solution = recover(peek(), ParserRuleContext.OBJECT_TYPE_QUALIFIER);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseObjectTypeQualifiers(solution.tokenKind);
        }

        return parseObjectTypeNextQualifiers(firstQualifier);
    }

    private STNode parseObjectTypeNextQualifiers(STNode firstQualifier) {
        List<STNode> qualifiers = new ArrayList<>();
        qualifiers.add(firstQualifier);

        // Parse the second and third qualifiers
        for (int i = 0; i < 2; i++) {
            STNode nextToken = peek();
            if (isNodeWithSyntaxKindInList(qualifiers, nextToken.kind)) {
                // Consumer the nextToken
                nextToken = consume();
                updateLastNodeInListWithInvalidNode(qualifiers, nextToken,
                        DiagnosticErrorCode.ERROR_SAME_OBJECT_TYPE_QUALIFIER);
                continue;
            }

            STNode nextQualifier;
            switch (nextToken.kind) {
                case CLIENT_KEYWORD:
                    nextQualifier = parseClientKeyword();
                    break;
                case ABSTRACT_KEYWORD:
                    nextQualifier = parseAbstractKeyword();
                    break;
                case READONLY_KEYWORD:
                    nextQualifier = parseReadonlyKeyword();
                    break;
                case OBJECT_KEYWORD:
                default:
                    return STNodeFactory.createNodeList(qualifiers);
            }
            qualifiers.add(nextQualifier);
        }

        return STNodeFactory.createNodeList(qualifiers);
    }

    /**
     * Parse client keyword.
     *
     * @return Parsed node
     */
    private STNode parseClientKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.CLIENT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CLIENT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse abstract keyword.
     *
     * @return Parsed node
     */
    private STNode parseAbstractKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ABSTRACT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ABSTRACT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse object keyword.
     *
     * @return Parsed node
     */
    private STNode parseObjectKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.OBJECT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.OBJECT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse object members.
     *
     * @return Parsed node
     */
    private STNode parseObjectMembers() {
        ArrayList<STNode> objectMembers = new ArrayList<>();
        while (!isEndOfObjectTypeNode()) {
            startContext(ParserRuleContext.OBJECT_MEMBER);
            STNode member = parseObjectMember(peek().kind);
            endContext();

            // Null member indicates the end of object members
            if (member == null) {
                break;
            }
            objectMembers.add(member);
        }

        return STNodeFactory.createNodeList(objectMembers);
    }

    private STNode parseObjectMember() {
        STToken nextToken = peek();
        return parseObjectMember(nextToken.kind);
    }

    private STNode parseObjectMember(SyntaxKind nextTokenKind) {
        STNode metadata;
        switch (nextTokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
                // Null return indicates the end of object members
                return null;
            case ASTERISK_TOKEN:
            case PUBLIC_KEYWORD:
            case PRIVATE_KEYWORD:
            case REMOTE_KEYWORD:
            case FUNCTION_KEYWORD:
            case TRANSACTIONAL_KEYWORD:
                metadata = createEmptyMetadata();
                break;
            case DOCUMENTATION_STRING:
            case AT_TOKEN:
                metadata = parseMetaData(nextTokenKind);
                nextTokenKind = peek().kind;
                break;
            default:
                if (isTypeStartingToken(nextTokenKind)) {
                    metadata = createEmptyMetadata();
                    break;
                }

                Solution solution = recover(peek(), ParserRuleContext.OBJECT_MEMBER_START);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseObjectMember(solution.tokenKind);
        }

        return parseObjectMember(nextTokenKind, metadata);
    }

    private STNode parseObjectMember(SyntaxKind nextTokenKind, STNode metadata) {
        STNode member;
        switch (nextTokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
                // TODO report metadata
                return null;
            case ASTERISK_TOKEN:
                STNode asterisk = consume();
                STNode type = parseTypeReference();
                STNode semicolonToken = parseSemicolon();
                member = STNodeFactory.createTypeReferenceNode(asterisk, type, semicolonToken);
                break;
            case PUBLIC_KEYWORD:
            case PRIVATE_KEYWORD:
                STNode visibilityQualifier = parseObjectMemberVisibility();
                member = parseObjectMethodOrField(metadata, visibilityQualifier);
                break;
            case REMOTE_KEYWORD:
                member = parseObjectMethodOrField(metadata, STNodeFactory.createEmptyNode());
                break;
            case FUNCTION_KEYWORD:
            case TRANSACTIONAL_KEYWORD:
                member = parseObjectMethod(metadata, new ArrayList<>());
                break;
            default:
                if (isTypeStartingToken(nextTokenKind)) {
                    member = parseObjectField(metadata, STNodeFactory.createEmptyNode());
                    break;
                }

                Solution solution = recover(peek(), ParserRuleContext.OBJECT_MEMBER_WITHOUT_METADATA);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseObjectMember(solution.tokenKind);
        }

        return member;
    }

    private STNode parseObjectMethodOrField(STNode metadata, STNode methodQualifiers) {
        STToken nextToken = peek(1);
        STToken nextNextToken = peek(2);
        return parseObjectMethodOrField(nextToken.kind, nextNextToken.kind, metadata, methodQualifiers);
    }

    /**
     * Parse an object member, given the visibility modifier. Object member can have
     * only one visibility qualifier. This mean the methodQualifiers list can have
     * one qualifier at-most.
     *
     * @param visibilityQualifier Visibility qualifier. A modifier can be
     *            a syntax node with either 'PUBLIC' or 'PRIVATE'.
     * @param nextTokenKind Next token kind
     * @param nextNextTokenKind Kind of the token after the
     * @param metadata Metadata
     * @param visibilityQualifier Visibility qualifiers
     * @return Parse object member node
     */
    private STNode parseObjectMethodOrField(SyntaxKind nextTokenKind, SyntaxKind nextNextTokenKind, STNode metadata,
                                            STNode visibilityQualifier) {
        List<STNode> qualifiers = new ArrayList<>();
        switch (nextTokenKind) {
            case REMOTE_KEYWORD:
                STNode remoteKeyword = parseRemoteKeyword();
                qualifiers.add(visibilityQualifier);
                qualifiers.add(remoteKeyword);
                return parseObjectMethod(metadata, qualifiers);
            case FUNCTION_KEYWORD:
            case TRANSACTIONAL_KEYWORD:
                qualifiers.add(visibilityQualifier);
                return parseObjectMethod(metadata, qualifiers);

            // All 'type starting tokens' here. should be same as 'parseTypeDescriptor(...)'
            case IDENTIFIER_TOKEN:
                if (nextNextTokenKind != SyntaxKind.OPEN_PAREN_TOKEN) {
                    // Here we try to catch the common user error of missing the function keyword.
                    // In such cases, lookahead for the open-parenthesis and figure out whether
                    // this is an object-method with missing name. If yes, then try to recover.
                    return parseObjectField(metadata, visibilityQualifier);
                }
                break;
            default:
                if (isTypeStartingToken(nextTokenKind)) {
                    return parseObjectField(metadata, visibilityQualifier);
                }
                break;
        }

        Solution solution = recover(peek(), ParserRuleContext.OBJECT_FUNC_OR_FIELD_WITHOUT_VISIBILITY, metadata,
                visibilityQualifier);

        // If the parser recovered by inserting a token, then try to re-parse the same
        // rule with the inserted token. This is done to pick the correct branch
        // to continue the parsing.
        if (solution.action == Action.REMOVE) {
            return solution.recoveredNode;
        }

        return parseObjectMethodOrField(solution.tokenKind, nextTokenKind, metadata, visibilityQualifier);
    }

    /**
     * Parse object visibility. Visibility can be <code>public</code> or <code>private</code>.
     *
     * @return Parsed node
     */
    private STNode parseObjectMemberVisibility() {
        STToken token = peek();
        if (token.kind == SyntaxKind.PUBLIC_KEYWORD || token.kind == SyntaxKind.PRIVATE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.PUBLIC_KEYWORD);
            return sol.recoveredNode;
        }
    }

    private STNode parseRemoteKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.REMOTE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.REMOTE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    private STNode parseObjectField(STNode metadata, STNode methodQualifiers) {
        STToken nextToken = peek();
        if (nextToken.kind != SyntaxKind.READONLY_KEYWORD) {
            STNode readonlyQualifier = STNodeFactory.createEmptyNode();
            STNode type = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_BEFORE_IDENTIFIER);
            STNode fieldName = parseVariableName();
            return parseObjectFieldRhs(metadata, methodQualifiers, readonlyQualifier, type, fieldName);
        }

        // If the readonly-keyword is present, check whether its qualifier
        // or the readonly-type-desc.
        STNode type;
        STNode readonlyQualifier = parseReadonlyKeyword();
        nextToken = peek();
        if (nextToken.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            STNode fieldNameOrTypeDesc = parseQualifiedIdentifier(ParserRuleContext.RECORD_FIELD_NAME_OR_TYPE_NAME);
            if (fieldNameOrTypeDesc.kind == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                // readonly a:b
                // Then treat "a:b" as the type-desc
                type = fieldNameOrTypeDesc;
            } else {
                // readonly a
                nextToken = peek();
                switch (nextToken.kind) {
                    case SEMICOLON_TOKEN: // readonly a;
                    case EQUAL_TOKEN: // readonly a =
                        // Then treat "readonly" as type-desc, and "a" as the field-name
                        type = createBuiltinSimpleNameReference(readonlyQualifier);
                        readonlyQualifier = STNodeFactory.createEmptyNode();
                        STNode fieldName = ((STSimpleNameReferenceNode) fieldNameOrTypeDesc).name;
                        return parseObjectFieldRhs(metadata, methodQualifiers, readonlyQualifier, type, fieldName);
                    default:
                        // else, treat a as the type-name
                        type = parseComplexTypeDescriptor(fieldNameOrTypeDesc,
                                ParserRuleContext.TYPE_DESC_IN_RECORD_FIELD, false);
                        break;
                }
            }
        } else if (isTypeStartingToken(nextToken.kind)) {
            type = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_RECORD_FIELD);
        } else {
            readonlyQualifier = createBuiltinSimpleNameReference(readonlyQualifier);
            type = parseComplexTypeDescriptor(readonlyQualifier, ParserRuleContext.TYPE_DESC_IN_RECORD_FIELD, false);
            readonlyQualifier = STNodeFactory.createEmptyNode();
        }

        STNode fieldName = parseVariableName();
        return parseObjectFieldRhs(metadata, methodQualifiers, readonlyQualifier, type, fieldName);
    }

    /**
     * Parse object field rhs, and complete the object field parsing. Returns the parsed object field.
     *
     * @param metadata Metadata
     * @param visibilityQualifier Visibility qualifier
     * @param readonlyQualifier Readonly qualifier
     * @param type Type descriptor
     * @param fieldName Field name
     * @return Parsed object field
     */
    private STNode parseObjectFieldRhs(STNode metadata, STNode visibilityQualifier, STNode readonlyQualifier,
                                       STNode type, STNode fieldName) {
        STToken nextToken = peek();
        return parseObjectFieldRhs(nextToken.kind, metadata, visibilityQualifier, readonlyQualifier, type, fieldName);
    }

    /**
     * Parse object field rhs, and complete the object field parsing. Returns the parsed object field.
     *
     * @param nextTokenKind Kind of the next token
     * @param metadata Metadata
     * @param visibilityQualifier Visibility qualifier
     * @param readonlyQualifier Readonly qualifier
     * @param type Type descriptor
     * @param fieldName Field name
     * @return Parsed object field
     */
    private STNode parseObjectFieldRhs(SyntaxKind nextTokenKind, STNode metadata, STNode visibilityQualifier,
                                       STNode readonlyQualifier, STNode type, STNode fieldName) {
        STNode equalsToken;
        STNode expression;
        STNode semicolonToken;
        switch (nextTokenKind) {
            case SEMICOLON_TOKEN:
                equalsToken = STNodeFactory.createEmptyNode();
                expression = STNodeFactory.createEmptyNode();
                semicolonToken = parseSemicolon();
                break;
            case EQUAL_TOKEN:
                equalsToken = parseAssignOp();
                expression = parseExpression();
                semicolonToken = parseSemicolon();
                break;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.OBJECT_FIELD_RHS, metadata, visibilityQualifier,
                        readonlyQualifier, type, fieldName);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseObjectFieldRhs(solution.tokenKind, metadata, visibilityQualifier, readonlyQualifier, type,
                        fieldName);
        }

        return STNodeFactory.createObjectFieldNode(metadata, visibilityQualifier, readonlyQualifier, type, fieldName,
                equalsToken, expression, semicolonToken);
    }

    private STNode parseObjectMethod(STNode metadata, List<STNode> qualifiers) {
        return parseFuncDefOrFuncTypeDesc(ParserRuleContext.OBJECT_METHOD_START_WITHOUT_REMOTE, metadata,
                true, qualifiers);
    }

    /**
     * Parse if-else statement.
     * <code>
     * if-else-stmt := if expression block-stmt [else-block]
     * </code>
     *
     * @return If-else block
     */
    private STNode parseIfElseBlock() {
        startContext(ParserRuleContext.IF_BLOCK);
        STNode ifKeyword = parseIfKeyword();
        STNode condition = parseExpression();
        STNode ifBody = parseBlockNode();
        endContext();

        STNode elseBody = parseElseBlock();
        return STNodeFactory.createIfElseStatementNode(ifKeyword, condition, ifBody, elseBody);
    }

    /**
     * Parse if-keyword.
     *
     * @return Parsed if-keyword node
     */
    private STNode parseIfKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.IF_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.IF_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse else-keyword.
     *
     * @return Parsed else keyword node
     */
    private STNode parseElseKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ELSE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ELSE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse block node.
     * <code>
     * block-stmt := { sequence-stmt }
     * sequence-stmt := statement*
     * </code>
     *
     * @return Parse block node
     */
    private STNode parseBlockNode() {
        startContext(ParserRuleContext.BLOCK_STMT);
        STNode openBrace = parseOpenBrace();
        STNode stmts = parseStatements();
        STNode closeBrace = parseCloseBrace();
        endContext();
        return STNodeFactory.createBlockStatementNode(openBrace, stmts, closeBrace);
    }

    /**
     * Parse else block.
     * <code>else-block := else (if-else-stmt | block-stmt)</code>
     *
     * @return Else block
     */
    private STNode parseElseBlock() {
        STToken nextToken = peek();
        if (nextToken.kind != SyntaxKind.ELSE_KEYWORD) {
            return STNodeFactory.createEmptyNode();
        }

        STNode elseKeyword = parseElseKeyword();
        STNode elseBody = parseElseBody();
        return STNodeFactory.createElseBlockNode(elseKeyword, elseBody);
    }

    /**
     * Parse else node body.
     * <code>else-body := if-else-stmt | block-stmt</code>
     *
     * @return Else node body
     */
    private STNode parseElseBody() {
        STToken nextToken = peek();
        return parseElseBody(nextToken.kind);
    }

    private STNode parseElseBody(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case IF_KEYWORD:
                return parseIfElseBlock();
            case OPEN_BRACE_TOKEN:
                return parseBlockNode();
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.ELSE_BODY);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseElseBody(solution.tokenKind);
        }
    }

    /**
     * Parse while statement.
     * <code>while-stmt := while expression block-stmt</code>
     *
     * @return While statement
     */
    private STNode parseWhileStatement() {
        startContext(ParserRuleContext.WHILE_BLOCK);
        STNode whileKeyword = parseWhileKeyword();
        STNode condition = parseExpression();
        STNode whileBody = parseBlockNode();
        endContext();
        return STNodeFactory.createWhileStatementNode(whileKeyword, condition, whileBody);
    }

    /**
     * Parse while-keyword.
     *
     * @return While-keyword node
     */
    private STNode parseWhileKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.WHILE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.WHILE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse panic statement.
     * <code>panic-stmt := panic expression ;</code>
     *
     * @return Panic statement
     */
    private STNode parsePanicStatement() {
        startContext(ParserRuleContext.PANIC_STMT);
        STNode panicKeyword = parsePanicKeyword();
        STNode expression = parseExpression();
        STNode semicolon = parseSemicolon();
        endContext();
        return STNodeFactory.createPanicStatementNode(panicKeyword, expression, semicolon);
    }

    /**
     * Parse panic-keyword.
     *
     * @return Panic-keyword node
     */
    private STNode parsePanicKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.PANIC_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.PANIC_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse check expression. This method is used to parse both check expression
     * as well as check action.
     *
     * <p>
     * <code>
     * checking-expr := checking-keyword expression
     * checking-action := checking-keyword action
     * </code>
     *
     * @param allowActions Allow actions
     * @param isRhsExpr Is rhs expression
     * @return Check expression node
     */
    private STNode parseCheckExpression(boolean isRhsExpr, boolean allowActions, boolean isInConditionalExpr) {
        STNode checkingKeyword = parseCheckingKeyword();
        STNode expr =
                parseExpression(OperatorPrecedence.EXPRESSION_ACTION, isRhsExpr, allowActions, isInConditionalExpr);
        if (isAction(expr)) {
            return STNodeFactory.createCheckExpressionNode(SyntaxKind.CHECK_ACTION, checkingKeyword, expr);
        } else {
            return STNodeFactory.createCheckExpressionNode(SyntaxKind.CHECK_EXPRESSION, checkingKeyword, expr);
        }
    }

    /**
     * Parse checking keyword.
     * <p>
     * <code>
     * checking-keyword := check | checkpanic
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseCheckingKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.CHECK_KEYWORD || token.kind == SyntaxKind.CHECKPANIC_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CHECKING_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse fail expression. This method is used to parse both fail expression
     * as well as fail action.
     *
     * <p>
     * <code>
     * fail-expr := fail-keyword expression
     * fail-action := fail-keyword action
     * </code>
     *
     * @param allowActions Allow actions
     * @param isRhsExpr Is rhs expression
     * @return Fail expression node
     */
    private STNode parseFailExpression(boolean isRhsExpr, boolean allowActions, boolean isInConditionalExpr) {

        STNode failKeyword = parseFailKeyword();
        STNode expr =
                parseExpression(OperatorPrecedence.EXPRESSION_ACTION, isRhsExpr, allowActions, isInConditionalExpr);
        if (isAction(expr)) {
            return STNodeFactory.createFailExpressionNode(SyntaxKind.FAIL_ACTION, failKeyword, expr);
        } else {
            return STNodeFactory.createFailExpressionNode(SyntaxKind.FAIL_EXPRESSION, failKeyword, expr);
        }
    }

    /**
     * Parse fail keyword.
     * <p>
     * <code>
     * fail-keyword := fail
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseFailKeyword() {

        STToken token = peek();
        if (token.kind == SyntaxKind.FAIL_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FAIL_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     *
     * Parse continue statement.
     * <code>continue-stmt := continue ; </code>
     *
     * @return continue statement
     */
    private STNode parseContinueStatement() {
        startContext(ParserRuleContext.CONTINUE_STATEMENT);
        STNode continueKeyword = parseContinueKeyword();
        STNode semicolon = parseSemicolon();
        endContext();
        return STNodeFactory.createContinueStatementNode(continueKeyword, semicolon);
    }

    /**
     * Parse continue-keyword.
     *
     * @return continue-keyword node
     */
    private STNode parseContinueKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.CONTINUE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CONTINUE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse return statement.
     * <code>return-stmt := return [ action-or-expr ] ;</code>
     *
     * @return Return statement
     */
    private STNode parseReturnStatement() {
        startContext(ParserRuleContext.RETURN_STMT);
        STNode returnKeyword = parseReturnKeyword();
        STNode returnRhs = parseReturnStatementRhs(returnKeyword);
        endContext();
        return returnRhs;
    }

    /**
     * Parse return-keyword.
     *
     * @return Return-keyword node
     */
    private STNode parseReturnKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.RETURN_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.RETURN_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse break statement.
     * <code>break-stmt := break ; </code>
     *
     * @return break statement
     */
    private STNode parseBreakStatement() {
        startContext(ParserRuleContext.BREAK_STATEMENT);
        STNode breakKeyword = parseBreakKeyword();
        STNode semicolon = parseSemicolon();
        endContext();
        return STNodeFactory.createBreakStatementNode(breakKeyword, semicolon);
    }

    /**
     * Parse break-keyword.
     *
     * @return break-keyword node
     */
    private STNode parseBreakKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.BREAK_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.BREAK_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * <p>
     * Parse the right hand side of a return statement.
     * </p>
     * <code>
     * return-stmt-rhs := ; |  action-or-expr ;
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseReturnStatementRhs(STNode returnKeyword) {
        STNode expr;
        STToken token = peek();

        switch (token.kind) {
            case SEMICOLON_TOKEN:
                expr = STNodeFactory.createEmptyNode();
                break;
            default:
                expr = parseActionOrExpression();
                break;
        }

        STNode semicolon = parseSemicolon();
        return STNodeFactory.createReturnStatementNode(returnKeyword, expr, semicolon);
    }

    /**
     * Parse mapping constructor expression.
     * <p>
     * <code>mapping-constructor-expr := { [field (, field)*] }</code>
     *
     * @return Parsed node
     */
    private STNode parseMappingConstructorExpr() {
        startContext(ParserRuleContext.MAPPING_CONSTRUCTOR);
        STNode openBrace = parseOpenBrace();
        STNode fields = parseMappingConstructorFields();
        STNode closeBrace = parseCloseBrace();
        endContext();
        return STNodeFactory.createMappingConstructorExpressionNode(openBrace, fields, closeBrace);
    }

    /**
     * Parse mapping constructor fields.
     *
     * @return Parsed node
     */
    private STNode parseMappingConstructorFields() {
        STToken nextToken = peek();
        if (isEndOfMappingConstructor(nextToken.kind)) {
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first field mapping, that has no leading comma
        List<STNode> fields = new ArrayList<>();
        STNode field = parseMappingField(ParserRuleContext.FIRST_MAPPING_FIELD);
        fields.add(field);

        return parseMappingConstructorFields(fields);
    }

    private STNode parseMappingConstructorFields(List<STNode> fields) {
        STToken nextToken;
        // Parse the remaining field mappings
        STNode mappingFieldEnd;
        nextToken = peek();
        while (!isEndOfMappingConstructor(nextToken.kind)) {
            mappingFieldEnd = parseMappingFieldEnd(nextToken.kind);
            if (mappingFieldEnd == null) {
                break;
            }
            fields.add(mappingFieldEnd);

            // Parse field
            STNode field = parseMappingField(ParserRuleContext.MAPPING_FIELD);
            fields.add(field);
            nextToken = peek();
        }

        return STNodeFactory.createNodeList(fields);
    }

    private STNode parseMappingFieldEnd() {
        return parseMappingFieldEnd(peek().kind);
    }

    private STNode parseMappingFieldEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACE_TOKEN:
                return null;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.MAPPING_FIELD_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseMappingFieldEnd(solution.tokenKind);

        }
    }

    private boolean isEndOfMappingConstructor(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case IDENTIFIER_TOKEN:
            case READONLY_KEYWORD:
                return false;
            case EOF_TOKEN:
            case DOCUMENTATION_STRING:
            case AT_TOKEN:
            case CLOSE_BRACE_TOKEN:
            case SEMICOLON_TOKEN:
            case PUBLIC_KEYWORD:
            case PRIVATE_KEYWORD:
            case FUNCTION_KEYWORD:
            case RETURNS_KEYWORD:
            case SERVICE_KEYWORD:
            case TYPE_KEYWORD:
            case LISTENER_KEYWORD:
            case CONST_KEYWORD:
            case FINAL_KEYWORD:
            case RESOURCE_KEYWORD:
                return true;
            default:
                return isSimpleType(tokenKind);
        }
    }

    /**
     * Parse mapping constructor field.
     * <p>
     * <code>field := specific-field | computed-name-field | spread-field</code>
     *
     * @param fieldContext Context of the mapping field
     * @param leadingComma Leading comma
     * @return Parsed node
     */
    private STNode parseMappingField(ParserRuleContext fieldContext) {
        STToken nextToken = peek();
        return parseMappingField(nextToken.kind, fieldContext);
    }

    private STNode parseMappingField(SyntaxKind tokenKind, ParserRuleContext fieldContext) {
        switch (tokenKind) {
            case IDENTIFIER_TOKEN:
                STNode readonlyKeyword = STNodeFactory.createEmptyNode();
                return parseSpecificFieldWithOptionalValue(readonlyKeyword);
            case STRING_LITERAL:
                readonlyKeyword = STNodeFactory.createEmptyNode();
                return parseQualifiedSpecificField(readonlyKeyword);
            // case FINAL_KEYWORD:
            case READONLY_KEYWORD:
                readonlyKeyword = parseReadonlyKeyword();
                return parseSpecificField(readonlyKeyword);
            case OPEN_BRACKET_TOKEN:
                return parseComputedField();
            case ELLIPSIS_TOKEN:
                STNode ellipsis = parseEllipsis();
                STNode expr = parseExpression();
                return STNodeFactory.createSpreadFieldNode(ellipsis, expr);
            case CLOSE_BRACE_TOKEN:
                if (fieldContext == ParserRuleContext.FIRST_MAPPING_FIELD) {
                    return null;
                }
                // else fall through
            default:
                STToken token = peek();
                Solution solution = recover(token, fieldContext, fieldContext);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseMappingField(solution.tokenKind, fieldContext);
        }
    }

    private STNode parseSpecificField(STNode readonlyKeyword) {
        STToken nextToken = peek();
        return parseSpecificField(nextToken.kind, readonlyKeyword);
    }

    private STNode parseSpecificField(SyntaxKind nextTokenKind, STNode readonlyKeyword) {
        switch (nextTokenKind) {
            case STRING_LITERAL:
                return parseQualifiedSpecificField(readonlyKeyword);
            case IDENTIFIER_TOKEN:
                return parseSpecificFieldWithOptionalValue(readonlyKeyword);
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.SPECIFIC_FIELD, readonlyKeyword);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseSpecificField(solution.tokenKind, readonlyKeyword);

        }
    }

    private STNode parseQualifiedSpecificField(STNode readonlyKeyword) {
        STNode key = parseStringLiteral();
        STNode colon = parseColon();
        STNode valueExpr = parseExpression();
        return STNodeFactory.createSpecificFieldNode(readonlyKeyword, key, colon, valueExpr);
    }

    /**
     * Parse mapping constructor specific-field with an optional value.
     *
     * @return Parsed node
     */
    private STNode parseSpecificFieldWithOptionalValue(STNode readonlyKeyword) {
        STNode key = parseIdentifier(ParserRuleContext.MAPPING_FIELD_NAME);
        return parseSpecificFieldRhs(readonlyKeyword, key);
    }

    private STNode parseSpecificFieldRhs(STNode readonlyKeyword, STNode key) {
        STToken nextToken = peek();
        return parseSpecificFieldRhs(nextToken.kind, readonlyKeyword, key);
    }

    private STNode parseSpecificFieldRhs(SyntaxKind tokenKind, STNode readonlyKeyword, STNode key) {
        STNode colon;
        STNode valueExpr;

        switch (tokenKind) {
            case COLON_TOKEN:
                colon = parseColon();
                valueExpr = parseExpression();
                break;
            case COMMA_TOKEN:
                colon = STNodeFactory.createEmptyNode();
                valueExpr = STNodeFactory.createEmptyNode();
                break;
            default:
                if (isEndOfMappingConstructor(tokenKind)) {
                    colon = STNodeFactory.createEmptyNode();
                    valueExpr = STNodeFactory.createEmptyNode();
                    break;
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.SPECIFIC_FIELD_RHS, readonlyKeyword, key);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseSpecificFieldRhs(solution.tokenKind, readonlyKeyword, key);

        }
        return STNodeFactory.createSpecificFieldNode(readonlyKeyword, key, colon, valueExpr);
    }

    /**
     * Parse string literal.
     *
     * @return Parsed node
     */
    private STNode parseStringLiteral() {
        STToken token = peek();
        if (token.kind == SyntaxKind.STRING_LITERAL) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.STRING_LITERAL);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse colon token.
     *
     * @return Parsed node
     */
    private STNode parseColon() {
        STToken token = peek();
        if (token.kind == SyntaxKind.COLON_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.COLON);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse readonly keyword.
     *
     * @return Parsed node
     */
    private STNode parseReadonlyKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.READONLY_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.READONLY_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse computed-name-field of a mapping constructor expression.
     * <p>
     * <code>computed-name-field := [ field-name-expr ] : value-expr</code>
     *
     * @param leadingComma Leading comma
     * @return Parsed node
     */
    private STNode parseComputedField() {
        // Parse computed field name
        startContext(ParserRuleContext.COMPUTED_FIELD_NAME);
        STNode openBracket = parseOpenBracket();
        STNode fieldNameExpr = parseExpression();
        STNode closeBracket = parseCloseBracket();
        endContext();

        // Parse rhs
        STNode colon = parseColon();
        STNode valueExpr = parseExpression();
        return STNodeFactory.createComputedNameFieldNode(openBracket, fieldNameExpr, closeBracket, colon, valueExpr);
    }

    /**
     * Parse open bracket.
     *
     * @return Parsed node
     */
    private STNode parseOpenBracket() {
        STToken token = peek();
        if (token.kind == SyntaxKind.OPEN_BRACKET_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.OPEN_BRACKET);
            return sol.recoveredNode;
        }
    }

    /**
     * <p>
     * Parse compound assignment statement, which takes the following format.
     * </p>
     * <code>assignment-stmt := lvexpr CompoundAssignmentOperator action-or-expr ;</code>
     *
     * @return Parsed node
     */
    private STNode parseCompoundAssignmentStmt() {
        startContext(ParserRuleContext.COMPOUND_ASSIGNMENT_STMT);
        STNode varName = parseVariableName();
        STNode compoundAssignmentStmt = parseCompoundAssignmentStmtRhs(varName);
        endContext();
        return compoundAssignmentStmt;
    }

    /**
     * <p>
     * Parse the RHS portion of the compound assignment.
     * </p>
     * <code>compound-assignment-stmt-rhs := CompoundAssignmentOperator action-or-expr ;</code>
     *
     * @param lvExpr LHS expression
     * @return Parsed node
     */
    private STNode parseCompoundAssignmentStmtRhs(STNode lvExpr) {
        STNode binaryOperator = parseCompoundBinaryOperator();
        STNode equalsToken = parseAssignOp();
        STNode expr = parseActionOrExpression();
        STNode semicolon = parseSemicolon();
        endContext();

        boolean lvExprValid = isValidLVExpr(lvExpr);
        if (!lvExprValid) {
            // Create a missing simple variable reference and attach the invalid lvExpr as minutiae
            STNode identifier = SyntaxErrors.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN);
            STNode simpleNameRef = STNodeFactory.createSimpleNameReferenceNode(identifier);
            lvExpr = SyntaxErrors.cloneWithLeadingInvalidNodeMinutiae(simpleNameRef, lvExpr,
                    DiagnosticErrorCode.ERROR_INVALID_EXPR_IN_COMPOUND_ASSIGNMENT_LHS);
        }
        return STNodeFactory.createCompoundAssignmentStatementNode(lvExpr, binaryOperator, equalsToken, expr,
                semicolon);
    }

    /**
     * Parse compound binary operator.
     * <code>BinaryOperator := + | - | * | / | & | | | ^ | << | >> | >>></code>
     *
     * @return Parsed node
     */
    private STNode parseCompoundBinaryOperator() {
        STToken token = peek();
        if (isCompoundBinaryOperator(token.kind)) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.COMPOUND_BINARY_OPERATOR);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse service declaration.
     * <p>
     * <code>
     * service-decl := metadata service [variable-name] on expression-list service-body-block
     * <br/>
     * expression-list := expression (, expression)*
     * </code>
     *
     * @param metadata Metadata
     * @return Parsed node
     */
    private STNode parseServiceDecl(STNode metadata) {
        startContext(ParserRuleContext.SERVICE_DECL);
        STNode serviceKeyword = parseServiceKeyword();
        STNode serviceDecl = parseServiceRhs(metadata, serviceKeyword);
        endContext();
        return serviceDecl;
    }

    /**
     * Parse rhs of the service declaration.
     * <p>
     * <code>
     * service-rhs := [variable-name] on expression-list service-body-block
     * </code>
     *
     * @param metadata Metadata
     * @param serviceKeyword Service keyword
     * @return Parsed node
     */
    private STNode parseServiceRhs(STNode metadata, STNode serviceKeyword) {
        STNode serviceName = parseServiceName();
        STNode onKeyword = parseOnKeyword();
        STNode expressionList = parseListeners();
        STNode serviceBody = parseServiceBody();

        onKeyword =
                cloneWithDiagnosticIfListEmpty(expressionList, onKeyword, DiagnosticErrorCode.ERROR_MISSING_EXPRESSION);
        return STNodeFactory.createServiceDeclarationNode(metadata, serviceKeyword, serviceName, onKeyword,
                expressionList, serviceBody);
    }

    private STNode parseServiceName() {
        STToken nextToken = peek();
        return parseServiceName(nextToken.kind);
    }

    private STNode parseServiceName(SyntaxKind kind) {
        switch (kind) {
            case IDENTIFIER_TOKEN:
                return parseIdentifier(ParserRuleContext.SERVICE_NAME);
            case ON_KEYWORD:
                return STNodeFactory.createEmptyNode();
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.OPTIONAL_SERVICE_NAME);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseServiceName(solution.tokenKind);
        }
    }

    /**
     * Parse service keyword.
     *
     * @return Parsed node
     */
    private STNode parseServiceKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.SERVICE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.SERVICE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Check whether the given token kind is a compound binary operator.
     * <p>
     * <code>compound-binary-operator := + | - | * | / | & | | | ^ | << | >> | >>></code>
     *
     * @param tokenKind STToken kind
     * @return <code>true</code> if the token kind refers to a binary operator. <code>false</code> otherwise
     */
    private boolean isCompoundBinaryOperator(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case PLUS_TOKEN:
            case MINUS_TOKEN:
            case SLASH_TOKEN:
            case ASTERISK_TOKEN:
            case BITWISE_AND_TOKEN:
            case BITWISE_XOR_TOKEN:
            case PIPE_TOKEN:
            case DOUBLE_LT_TOKEN:
            case DOUBLE_GT_TOKEN:
            case TRIPPLE_GT_TOKEN:
                return getNextNextToken(tokenKind).kind == SyntaxKind.EQUAL_TOKEN;
            default:
                return false;
        }
    }

    /**
     * Parse on keyword.
     *
     * @return Parsed node
     */
    private STNode parseOnKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ON_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ON_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse listener references.
     * <p>
     * <code>expression-list := expression (, expression)*</code>
     *
     * @return Parsed node
     */
    private STNode parseListeners() {
        // TODO: Change body to align with parseOptionalExpressionsList()
        startContext(ParserRuleContext.LISTENERS_LIST);
        List<STNode> listeners = new ArrayList<>();

        STToken nextToken = peek();
        if (isEndOfExpressionsList(nextToken.kind)) {
            endContext();
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first expression, that has no leading comma
        STNode leadingComma = STNodeFactory.createEmptyNode();
        STNode exprListItem = parseExpressionListItem(leadingComma);
        listeners.add(exprListItem);

        // Parse the remaining expressions
        nextToken = peek();
        while (!isEndOfExpressionsList(nextToken.kind)) {
            leadingComma = parseComma();
            exprListItem = parseExpressionListItem(leadingComma);
            listeners.add(exprListItem);
            nextToken = peek();
        }

        endContext();
        return STNodeFactory.createNodeList(listeners);
    }

    private boolean isEndOfExpressionsList(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case COMMA_TOKEN:
                return false;
            case EOF_TOKEN:
            case SEMICOLON_TOKEN:
            case CLOSE_BRACKET_TOKEN:
            case CLOSE_BRACE_TOKEN:
            case CLOSE_PAREN_TOKEN:
            case OPEN_BRACE_TOKEN:
                return true;
            default:
                return !isValidExprStart(tokenKind);
        }
    }

    /**
     * Parse expression list item.
     *
     * @param leadingComma Leading comma
     * @return Parsed node
     */
    private STNode parseExpressionListItem(STNode leadingComma) {
        STNode expr = parseExpression();
        return STNodeFactory.createExpressionListItemNode(leadingComma, expr);
    }

    /**
     * Parse service body.
     * <p>
     * <code>
     * service-body-block := { service-method-defn* }
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseServiceBody() {
        STNode openBrace = parseOpenBrace();
        STNode resources = parseResources();
        STNode closeBrace = parseCloseBrace();
        return STNodeFactory.createServiceBodyNode(openBrace, resources, closeBrace);
    }

    /**
     * Parse service resource definitions.
     *
     * @return Parsed node
     */
    private STNode parseResources() {
        List<STNode> resources = new ArrayList<>();
        STToken nextToken = peek();
        while (!isEndOfServiceDecl(nextToken.kind)) {
            STNode serviceMethod = parseResource();
            if (serviceMethod == null) {
                break;
            }
            resources.add(serviceMethod);
            nextToken = peek();
        }

        return STNodeFactory.createNodeList(resources);
    }

    private boolean isEndOfServiceDecl(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case CLOSE_BRACE_TOKEN:
            case EOF_TOKEN:
            case CLOSE_BRACE_PIPE_TOKEN:
            case TYPE_KEYWORD:
            case SERVICE_KEYWORD:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse resource definition (i.e. service-method-defn).
     * <p>
     * <code>
     * service-body-block := { service-method-defn* }
     * <br/>
     * service-method-defn := metadata [resource] function identifier function-signature method-defn-body
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseResource() {
        STToken nextToken = peek();
        return parseResource(nextToken.kind);
    }

    private STNode parseResource(SyntaxKind nextTokenKind) {
        STNode metadata;
        switch (nextTokenKind) {
            case RESOURCE_KEYWORD:
            case TRANSACTIONAL_KEYWORD:
            case FUNCTION_KEYWORD:
                metadata = createEmptyMetadata();
                break;
            case DOCUMENTATION_STRING:
            case AT_TOKEN:
                metadata = parseMetaData(nextTokenKind);
                nextTokenKind = peek().kind;
                break;
            default:
                if (isEndOfServiceDecl(nextTokenKind)) {
                    return null;
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.RESOURCE_DEF);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseResource(solution.tokenKind);
        }

        return parseResource(nextTokenKind, metadata);
    }

    private STNode parseResource(SyntaxKind nextTokenKind, STNode metadata) {
        List<STNode> qualifiers = new ArrayList<>();
        switch (nextTokenKind) {
            case RESOURCE_KEYWORD:
                qualifiers.add(parseResourceKeyword());
                break;
            case TRANSACTIONAL_KEYWORD:
                qualifiers.add(parseTransactionalKeyword());
                qualifiers.add(parseResourceKeyword());
                break;
            case FUNCTION_KEYWORD:
                break;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.RESOURCE_DEF, metadata);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseResource(solution.tokenKind, metadata);
        }
        return parseFuncDefinition(metadata, false, qualifiers);
    }

    /**
     * Parse resource keyword.
     *
     * @return Parsed node
     */
    private STNode parseResourceKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.RESOURCE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.RESOURCE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Check whether next construct is a service declaration or not. This method is
     * used to determine whether an end-of-block is reached, if the next token is
     * a service-keyword. Because service-keyword can be used in statements as well
     * as in top-level node (service-decl). We have reached a service-decl, then
     * it could be due to missing close-brace at the end of the current block.
     *
     * @return <code>true</code> if the next construct is a service declaration.
     *         <code>false</code> otherwise
     */
    private boolean isServiceDeclStart(ParserRuleContext currentContext, int lookahead) {
        // Assume we always reach here after a peek()
        switch (peek(lookahead + 1).kind) {
            case IDENTIFIER_TOKEN:
                SyntaxKind tokenAfterIdentifier = peek(lookahead + 2).kind;
                switch (tokenAfterIdentifier) {
                    case ON_KEYWORD: // service foo on ...
                    case OPEN_BRACE_TOKEN: // missing listeners--> service foo {
                        return true;
                    case EQUAL_TOKEN: // service foo = ...
                    case SEMICOLON_TOKEN: // service foo;
                    case QUESTION_MARK_TOKEN: // service foo?;
                        return false;
                    default:
                        // If not any of above, this is not a valid syntax.
                        return false;
                }
            case ON_KEYWORD:
                // Next token sequence is similar to: `service on ...`.
                // Then this is a service decl.
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse listener declaration, given the qualifier.
     * <p>
     * <code>
     * listener-decl := metadata [public] listener [type-descriptor] variable-name = expression ;
     * </code>
     *
     * @param metadata Metadata
     * @param qualifier Qualifier that precedes the listener declaration
     * @return Parsed node
     */
    private STNode parseListenerDeclaration(STNode metadata, STNode qualifier) {
        startContext(ParserRuleContext.LISTENER_DECL);
        STNode listenerKeyword = parseListenerKeyword();

        if (peek().kind == SyntaxKind.IDENTIFIER_TOKEN) {
            STNode listenerDecl =
                    parseConstantOrListenerDeclWithOptionalType(metadata, qualifier, listenerKeyword, true);
            endContext();
            return listenerDecl;
        }

        STNode typeDesc = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_BEFORE_IDENTIFIER);
        STNode variableName = parseVariableName();
        STNode equalsToken = parseAssignOp();
        STNode initializer = parseExpression();
        STNode semicolonToken = parseSemicolon();
        endContext();
        return STNodeFactory.createListenerDeclarationNode(metadata, qualifier, listenerKeyword, typeDesc, variableName,
                equalsToken, initializer, semicolonToken);
    }

    /**
     * Parse listener keyword.
     *
     * @return Parsed node
     */
    private STNode parseListenerKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.LISTENER_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.LISTENER_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse constant declaration, given the qualifier.
     * <p>
     * <code>module-const-decl := metadata [public] const [type-descriptor] identifier = const-expr ;</code>
     *
     * @param metadata Metadata
     * @param qualifier Qualifier that precedes the listener declaration
     * @return Parsed node
     */
    private STNode parseConstantDeclaration(STNode metadata, STNode qualifier) {
        startContext(ParserRuleContext.CONSTANT_DECL);
        STNode constKeyword = parseConstantKeyword();
        STNode constDecl = parseConstDecl(metadata, qualifier, constKeyword);
        endContext();
        return constDecl;
    }

    /**
     * Parse the components that follows after the const keyword of a constant declaration.
     *
     * @param metadata Metadata
     * @param qualifier Qualifier that precedes the constant decl
     * @param constKeyword Const keyword
     * @return Parsed node
     */
    private STNode parseConstDecl(STNode metadata, STNode qualifier, STNode constKeyword) {
        STToken nextToken = peek();
        return parseConstDeclFromType(nextToken.kind, metadata, qualifier, constKeyword);
    }

    private STNode parseConstDeclFromType(SyntaxKind nextTokenKind, STNode metadata, STNode qualifier, STNode keyword) {
        switch (nextTokenKind) {
            case ANNOTATION_KEYWORD:
                switchContext(ParserRuleContext.ANNOTATION_DECL);
                return parseAnnotationDeclaration(metadata, qualifier, keyword);
            case IDENTIFIER_TOKEN:
                return parseConstantOrListenerDeclWithOptionalType(metadata, qualifier, keyword, false);
            default:
                if (isTypeStartingToken(nextTokenKind)) {
                    break;
                }
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.CONST_DECL_TYPE, metadata, qualifier, keyword);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseConstDeclFromType(solution.tokenKind, metadata, qualifier, keyword);
        }

        STNode typeDesc = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_BEFORE_IDENTIFIER);
        STNode variableName = parseVariableName();
        STNode equalsToken = parseAssignOp();
        STNode initializer = parseExpression();
        STNode semicolonToken = parseSemicolon();
        return STNodeFactory.createConstantDeclarationNode(metadata, qualifier, keyword, typeDesc, variableName,
                equalsToken, initializer, semicolonToken);
    }

    private STNode parseConstantOrListenerDeclWithOptionalType(STNode metadata, STNode qualifier, STNode constKeyword,
                                                               boolean isListener) {
        STNode varNameOrTypeName = parseStatementStartIdentifier();
        STNode constDecl =
                parseConstantOrListenerDeclRhs(metadata, qualifier, constKeyword, varNameOrTypeName, isListener);
        return constDecl;
    }

    /**
     * Parse the component that follows the first identifier in a const decl. The identifier
     * can be either the type-name (a user defined type) or the var-name there the type-name
     * is not present.
     *
     * @param qualifier Qualifier that precedes the constant decl
     * @param keyword Keyword
     * @param typeOrVarName Identifier that follows the const-keywoord
     * @return Parsed node
     */
    private STNode parseConstantOrListenerDeclRhs(STNode metadata, STNode qualifier, STNode keyword,
                                                  STNode typeOrVarName, boolean isListener) {
        if (typeOrVarName.kind == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
            STNode type = typeOrVarName;
            STNode variableName = parseVariableName();
            return parseListenerOrConstRhs(metadata, qualifier, keyword, isListener, type, variableName);
        }

        STToken token = peek();
        return parseConstantOrListenerDeclRhs(token.kind, metadata, qualifier, keyword, typeOrVarName, isListener);
    }

    private STNode parseConstantOrListenerDeclRhs(SyntaxKind nextTokenKind, STNode metadata, STNode qualifier,
                                                  STNode keyword, STNode typeOrVarName, boolean isListener) {
        STNode type;
        STNode variableName;
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN:
                type = typeOrVarName;
                variableName = parseVariableName();
                break;
            case EQUAL_TOKEN:
                variableName = ((STSimpleNameReferenceNode) typeOrVarName).name; // variableName is a token
                type = STNodeFactory.createEmptyNode();
                break;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.CONST_DECL_RHS, metadata, qualifier, keyword,
                        typeOrVarName, isListener);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseConstantOrListenerDeclRhs(solution.tokenKind, metadata, qualifier, keyword, typeOrVarName,
                        isListener);
        }

        return parseListenerOrConstRhs(metadata, qualifier, keyword, isListener, type, variableName);
    }

    private STNode parseListenerOrConstRhs(STNode metadata, STNode qualifier, STNode keyword, boolean isListener,
                                           STNode type, STNode variableName) {
        STNode equalsToken = parseAssignOp();
        STNode initializer = parseExpression();
        STNode semicolonToken = parseSemicolon();

        if (isListener) {
            return STNodeFactory.createListenerDeclarationNode(metadata, qualifier, keyword, type, variableName,
                    equalsToken, initializer, semicolonToken);
        }

        return STNodeFactory.createConstantDeclarationNode(metadata, qualifier, keyword, type, variableName,
                equalsToken, initializer, semicolonToken);
    }

    /**
     * Parse const keyword.
     *
     * @return Parsed node
     */
    private STNode parseConstantKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.CONST_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CONST_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse nil type descriptor.
     * <p>
     * <code>nil-type-descriptor :=  ( ) </code>
     * </p>
     *
     * @return Parsed node
     */
    private STNode parseNilTypeDescriptor() {
        startContext(ParserRuleContext.NIL_TYPE_DESCRIPTOR);
        STNode openParenthesisToken = parseOpenParenthesis(ParserRuleContext.OPEN_PARENTHESIS);
        STNode closeParenthesisToken = parseCloseParenthesis();
        endContext();
        return STNodeFactory.createNilTypeDescriptorNode(openParenthesisToken, closeParenthesisToken);
    }

    /**
     * Parse typeof expression.
     * <p>
     * <code>
     * typeof-expr := typeof expression
     * </code>
     *
     * @param isRhsExpr
     * @return Typeof expression node
     */
    private STNode parseTypeofExpression(boolean isRhsExpr, boolean isInConditionalExpr) {
        STNode typeofKeyword = parseTypeofKeyword();

        // allow-actions flag is always false, since there will not be any actions
        // within the typeof-expression, due to the precedence.
        STNode expr = parseExpression(OperatorPrecedence.UNARY, isRhsExpr, false, isInConditionalExpr);
        return STNodeFactory.createTypeofExpressionNode(typeofKeyword, expr);
    }

    /**
     * Parse typeof-keyword.
     *
     * @return Typeof-keyword node
     */
    private STNode parseTypeofKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.TYPEOF_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.TYPEOF_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse optional type descriptor.
     * <p>
     * <code>optional-type-descriptor := type-descriptor ? </code>
     * </p>
     *
     * @return Parsed node
     */
    private STNode parseOptionalTypeDescriptor(STNode typeDescriptorNode) {
        startContext(ParserRuleContext.OPTIONAL_TYPE_DESCRIPTOR);
        STNode questionMarkToken = parseQuestionMark();
        endContext();
        typeDescriptorNode = validateForUsageOfVar(typeDescriptorNode);
        return STNodeFactory.createOptionalTypeDescriptorNode(typeDescriptorNode, questionMarkToken);
    }

    /**
     * Parse unary expression.
     * <p>
     * <code>
     * unary-expr := + expression | - expression | ~ expression | ! expression
     * </code>
     *
     * @param isRhsExpr
     * @return Unary expression node
     */
    private STNode parseUnaryExpression(boolean isRhsExpr, boolean isInConditionalExpr) {
        STNode unaryOperator = parseUnaryOperator();

        // allow-actions flag is always false, since there will not be any actions
        // within the unary expression, due to the precedence.
        STNode expr = parseExpression(OperatorPrecedence.UNARY, isRhsExpr, false, isInConditionalExpr);
        return STNodeFactory.createUnaryExpressionNode(unaryOperator, expr);
    }

    /**
     * Parse unary operator.
     * <code>UnaryOperator := + | - | ~ | !</code>
     *
     * @return Parsed node
     */
    private STNode parseUnaryOperator() {
        STToken token = peek();
        if (isUnaryOperator(token.kind)) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.UNARY_OPERATOR);
            return sol.recoveredNode;
        }
    }

    /**
     * Check whether the given token kind is a unary operator.
     *
     * @param kind STToken kind
     * @return <code>true</code> if the token kind refers to a unary operator. <code>false</code> otherwise
     */
    private boolean isUnaryOperator(SyntaxKind kind) {
        switch (kind) {
            case PLUS_TOKEN:
            case MINUS_TOKEN:
            case NEGATION_TOKEN:
            case EXCLAMATION_MARK_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse array type descriptor.
     * <p>
     * <code>
     * array-type-descriptor := member-type-descriptor [ [ array-length ] ]
     * member-type-descriptor := type-descriptor
     * array-length :=
     *    int-literal
     *    | constant-reference-expr
     *    | inferred-array-length
     * inferred-array-length := *
     * </code>
     * </p>
     *
     * @param memberTypeDesc
     *
     * @return Parsed Node
     */
    private STNode parseArrayTypeDescriptor(STNode memberTypeDesc) {
        startContext(ParserRuleContext.ARRAY_TYPE_DESCRIPTOR);
        STNode openBracketToken = parseOpenBracket();
        STNode arrayLengthNode = parseArrayLength();
        STNode closeBracketToken = parseCloseBracket();
        endContext();
        return createArrayTypeDesc(memberTypeDesc, openBracketToken, arrayLengthNode, closeBracketToken);
    }

    private STNode createArrayTypeDesc(STNode memberTypeDesc, STNode openBracketToken, STNode arrayLengthNode,
                                       STNode closeBracketToken) {
        memberTypeDesc = validateForUsageOfVar(memberTypeDesc);
        return STNodeFactory.createArrayTypeDescriptorNode(memberTypeDesc, openBracketToken, arrayLengthNode,
                closeBracketToken);
    }

    /**
     * Parse array length.
     * <p>
     * <code>
     *     array-length :=
     *    int-literal
     *    | constant-reference-expr
     *    | inferred-array-length
     * constant-reference-expr := variable-reference-expr
     * </code>
     * </p>
     *
     * @return Parsed array length
     */
    private STNode parseArrayLength() {
        STToken token = peek();
        switch (token.kind) {
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case ASTERISK_TOKEN:
                return parseBasicLiteral();
            case CLOSE_BRACKET_TOKEN:
                return STNodeFactory.createEmptyNode();
            // Parsing variable-reference-expr is same as parsing qualified identifier
            case IDENTIFIER_TOKEN:
                return parseQualifiedIdentifier(ParserRuleContext.ARRAY_LENGTH);
            default:
                Solution sol = recover(token, ParserRuleContext.ARRAY_LENGTH);
                return sol.recoveredNode;
        }
    }

    /**
     * Parse annotations.
     * <p>
     * <i>Note: In the ballerina spec ({@link https://ballerina.io/spec/lang/2020R1/#annots})
     * annotations-list is specified as one-or-more annotations. And the usage is marked as
     * optional annotations-list. However, for the consistency of the tree, here we make the
     * annotation-list as zero-or-more annotations, and the usage is not-optional.</i>
     * <p>
     * <code>annots := annotation*</code>
     *
     * @return Parsed node
     */
    private STNode parseOptionalAnnotations() {
        STToken nextToken = peek();
        return parseOptionalAnnotations(nextToken.kind);
    }

    private STNode parseOptionalAnnotations(SyntaxKind nextTokenKind) {
        startContext(ParserRuleContext.ANNOTATIONS);
        List<STNode> annotList = new ArrayList<>();
        while (nextTokenKind == SyntaxKind.AT_TOKEN) {
            annotList.add(parseAnnotation());
            nextTokenKind = peek().kind;
        }

        endContext();
        return STNodeFactory.createNodeList(annotList);
    }

    /**
     * Parse annotation list with atleast one annotation.
     * 
     * @return Annotation list
     */
    private STNode parseAnnotations() {
        startContext(ParserRuleContext.ANNOTATIONS);
        List<STNode> annotList = new ArrayList<>();
        annotList.add(parseAnnotation());
        while (peek().kind == SyntaxKind.AT_TOKEN) {
            annotList.add(parseAnnotation());
        }

        endContext();
        return STNodeFactory.createNodeList(annotList);
    }

    /**
     * Parse annotation attachment.
     * <p>
     * <code>annotation := @ annot-tag-reference annot-value</code>
     *
     * @return Parsed node
     */
    private STNode parseAnnotation() {
        STNode atToken = parseAtToken();
        STNode annotReference;
        if (peek().kind != SyntaxKind.IDENTIFIER_TOKEN) {
            annotReference = STNodeFactory.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN);
        } else {
            annotReference = parseQualifiedIdentifier(ParserRuleContext.ANNOT_REFERENCE);
        }

        STNode annotValue;
        if (peek().kind == SyntaxKind.OPEN_BRACE_TOKEN) {
            annotValue = parseMappingConstructorExpr();
        } else {
            annotValue = STNodeFactory.createEmptyNode();
        }
        return STNodeFactory.createAnnotationNode(atToken, annotReference, annotValue);
    }

    /**
     * Parse '@' token.
     *
     * @return Parsed node
     */
    private STNode parseAtToken() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.AT_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(nextToken, ParserRuleContext.AT);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse metadata. Meta data consist of optional doc string and
     * an annotations list.
     * <p>
     * <code>metadata := [DocumentationString] annots</code>
     *
     * @return Parse node
     */
    private STNode parseMetaData(SyntaxKind nextTokenKind) {
        STNode docString;
        STNode annotations;
        switch (nextTokenKind) {
            case DOCUMENTATION_STRING:
                docString = parseMarkdownDocumentation();
                annotations = parseOptionalAnnotations();
                break;
            case AT_TOKEN:
                docString = STNodeFactory.createEmptyNode();
                annotations = parseOptionalAnnotations(nextTokenKind);
                break;
            default:
                return createEmptyMetadata();
        }

        return STNodeFactory.createMetadataNode(docString, annotations);
    }

    /**
     * Create empty metadata node.
     *
     * @return A metadata node with no doc string and no annotations
     */
    private STNode createEmptyMetadata() {
        return STNodeFactory.createMetadataNode(STNodeFactory.createEmptyNode(), STNodeFactory.createEmptyNodeList());
    }

    /**
     * Parse is expression.
     * <code>
     * is-expr := expression is type-descriptor
     * </code>
     *
     * @param lhsExpr Preceding expression of the is expression
     * @return Is expression node
     */
    private STNode parseTypeTestExpression(STNode lhsExpr, boolean isInConditionalExpr) {
        STNode isKeyword = parseIsKeyword();
        STNode typeDescriptor =
                parseTypeDescriptorInExpression(ParserRuleContext.TYPE_DESC_IN_EXPRESSION, isInConditionalExpr);
        return STNodeFactory.createTypeTestExpressionNode(lhsExpr, isKeyword, typeDescriptor);
    }

    /**
     * Parse is-keyword.
     *
     * @return Is-keyword node
     */
    private STNode parseIsKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.IS_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.IS_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse local type definition statement statement.
     * <code>ocal-type-defn-stmt := [annots] type identifier type-descriptor ;</code>
     *
     * @return local type definition statement statement
     */
    private STNode parseLocalTypeDefinitionStatement(STNode annots) {
        startContext(ParserRuleContext.LOCAL_TYPE_DEFINITION_STMT);
        STNode typeKeyword = parseTypeKeyword();
        STNode typeName = parseTypeName();
        STNode typeDescriptor = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TYPE_DEF);
        STNode semicolon = parseSemicolon();
        endContext();
        return STNodeFactory.createLocalTypeDefinitionStatementNode(annots, typeKeyword, typeName, typeDescriptor,
                semicolon);
    }

    /**
     * Parse statement which is only consists of an action or expression.
     *
     * @param annots Annotations
     * @param nextTokenKind Next token kind
     * @return Statement node
     */
    private STNode parseExpressionStatement(SyntaxKind nextTokenKind, STNode annots) {
        startContext(ParserRuleContext.EXPRESSION_STATEMENT);
        STNode expression = parseActionOrExpressionInLhs(nextTokenKind, annots);
        return getExpressionAsStatement(expression);
    }

    /**
     * Parse statements that starts with an expression.
     *
     * @return Statement node
     */
    private STNode parseStatementStartWithExpr(STNode annots) {
        startContext(ParserRuleContext.AMBIGUOUS_STMT);
        STNode expr = parseActionOrExpressionInLhs(peek().kind, annots);
        return parseStatementStartWithExprRhs(expr);
    }

    /**
     * Parse rhs of statements that starts with an expression.
     *
     * @return Statement node
     */
    private STNode parseStatementStartWithExprRhs(STNode expression) {
        STToken nextToken = peek();
        return parseStatementStartWithExprRhs(nextToken.kind, expression);
    }

    /**
     * Parse the component followed by the expression, at the beginning of a statement.
     *
     * @param nextTokenKind Kind of the next token
     * @return Statement node
     */
    private STNode parseStatementStartWithExprRhs(SyntaxKind nextTokenKind, STNode expression) {
        switch (nextTokenKind) {
            case EQUAL_TOKEN:
                switchContext(ParserRuleContext.ASSIGNMENT_STMT);
                return parseAssignmentStmtRhs(expression);
            case SEMICOLON_TOKEN:
                return getExpressionAsStatement(expression);
            case IDENTIFIER_TOKEN:
            default:
                // If its a binary operator then this can be a compound assignment statement
                if (isCompoundBinaryOperator(nextTokenKind)) {
                    return parseCompoundAssignmentStmtRhs(expression);
                }

                ParserRuleContext context;
                if (isPossibleExpressionStatement(expression)) {
                    context = ParserRuleContext.EXPR_STMT_RHS;
                } else {
                    context = ParserRuleContext.STMT_START_WITH_EXPR_RHS;
                }
                Solution solution = recover(peek(), context, expression);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseStatementStartWithExprRhs(solution.tokenKind, expression);
        }
    }

    private boolean isPossibleExpressionStatement(STNode expression) {
        switch (expression.kind) {
            case METHOD_CALL:
            case FUNCTION_CALL:
            case CHECK_EXPRESSION:
            case FAIL_EXPRESSION:
            case REMOTE_METHOD_CALL_ACTION:
            case CHECK_ACTION:
            case FAIL_ACTION:
            case BRACED_ACTION:
            case START_ACTION:
            case TRAP_ACTION:
            case FLUSH_ACTION:
            case ASYNC_SEND_ACTION:
            case SYNC_SEND_ACTION:
            case RECEIVE_ACTION:
            case WAIT_ACTION:
            case QUERY_ACTION:
            case COMMIT_ACTION:
                return true;
            default:
                return false;
        }
    }

    private STNode getExpressionAsStatement(STNode expression) {
        switch (expression.kind) {
            case METHOD_CALL:
            case FUNCTION_CALL:
            case CHECK_EXPRESSION:
                return parseCallStatement(expression);
            case REMOTE_METHOD_CALL_ACTION:
            case CHECK_ACTION:
            case FAIL_ACTION:
            case BRACED_ACTION:
            case START_ACTION:
            case TRAP_ACTION:
            case FLUSH_ACTION:
            case ASYNC_SEND_ACTION:
            case SYNC_SEND_ACTION:
            case RECEIVE_ACTION:
            case WAIT_ACTION:
            case QUERY_ACTION:
            case COMMIT_ACTION:
            case FAIL_EXPRESSION:
                return parseActionStatement(expression);
            default:
                // Everything else can not be written as a statement.
                STNode semicolon = parseSemicolon();
                endContext();
                STNode exprStmt = STNodeFactory.createExpressionStatementNode(SyntaxKind.INVALID_EXPRESSION_STATEMENT,
                        expression, semicolon);
                exprStmt = SyntaxErrors.addDiagnostic(exprStmt, DiagnosticErrorCode.ERROR_INVALID_EXPRESSION_STATEMENT);
                return exprStmt;
        }
    }

    private STNode parseArrayTypeDescriptorNode(STIndexedExpressionNode indexedExpr) {
        STNode memberTypeDesc = getTypeDescFromExpr(indexedExpr.containerExpression);
        STNodeList lengthExprs = (STNodeList) indexedExpr.keyExpression;
        if (lengthExprs.isEmpty()) {
            return createArrayTypeDesc(memberTypeDesc, indexedExpr.openBracket, STNodeFactory.createEmptyNode(),
                    indexedExpr.closeBracket);
        }

        // Validate the array length expression
        STNode lengthExpr = lengthExprs.get(0);
        switch (lengthExpr.kind) {
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case ASTERISK_TOKEN:
            case SIMPLE_NAME_REFERENCE:
            case QUALIFIED_NAME_REFERENCE:
                break;
            default:
                STNode newOpenBracketWithDiagnostics = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(
                        indexedExpr.openBracket, lengthExpr, DiagnosticErrorCode.ERROR_INVALID_ARRAY_LENGTH);
                indexedExpr = indexedExpr.replace(indexedExpr.openBracket, newOpenBracketWithDiagnostics);
                lengthExpr = STNodeFactory.createEmptyNode();
        }

        return createArrayTypeDesc(memberTypeDesc, indexedExpr.openBracket, lengthExpr, indexedExpr.closeBracket);
    }

    /**
     * <p>
     * Parse call statement, given the call expression.
     * <p>
     * <code>
     * call-stmt := call-expr ;
     * <br/>
     * call-expr := function-call-expr | method-call-expr | checking-keyword call-expr
     * </code>
     *
     * @param expression Call expression associated with the call statement
     * @return Call statement node
     */
    private STNode parseCallStatement(STNode expression) {
        // TODO Validate the expression.
        // This is not a must because this expression is validated in the semantic analyzer.
        STNode semicolon = parseSemicolon();
        endContext();
        return STNodeFactory.createExpressionStatementNode(SyntaxKind.CALL_STATEMENT, expression, semicolon);
    }

    private STNode parseActionStatement(STNode action) {
        STNode semicolon = parseSemicolon();
        endContext();
        return STNodeFactory.createExpressionStatementNode(SyntaxKind.ACTION_STATEMENT, action, semicolon);
    }

    /**
     * Parse remote method call action, given the starting expression.
     * <p>
     * <code>
     * remote-method-call-action := expression -> method-name ( arg-list )
     * <br/>
     * async-send-action := expression -> peer-worker ;
     * </code>
     *
     * @param isRhsExpr Is this an RHS action
     * @param expression LHS expression
     * @return
     */
    private STNode parseRemoteMethodCallOrAsyncSendAction(STNode expression, boolean isRhsExpr) {
        STNode rightArrow = parseRightArrow();
        return parseRemoteCallOrAsyncSendActionRhs(expression, isRhsExpr, rightArrow);
    }

    private STNode parseRemoteCallOrAsyncSendActionRhs(STNode expression, boolean isRhsExpr, STNode rightArrow) {
        return parseRemoteCallOrAsyncSendActionRhs(peek().kind, expression, isRhsExpr, rightArrow);
    }

    private STNode parseRemoteCallOrAsyncSendActionRhs(SyntaxKind nextTokenKind, STNode expression, boolean isRhsExpr,
                                                       STNode rightArrow) {
        STNode name;
        switch (nextTokenKind) {
            case DEFAULT_KEYWORD:
                STNode defaultKeyword = parseDefaultKeyword();
                name = STNodeFactory.createSimpleNameReferenceNode(defaultKeyword);
                return parseAsyncSendAction(expression, rightArrow, name);
            case IDENTIFIER_TOKEN:
                name = STNodeFactory.createSimpleNameReferenceNode(parseFunctionName());
                break;
            case CONTINUE_KEYWORD:
            case COMMIT_KEYWORD:
                name = getKeywordAsSimpleNameRef();
                break;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.REMOTE_CALL_OR_ASYNC_SEND_RHS, expression,
                        isRhsExpr, rightArrow);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    name = solution.recoveredNode;
                    break;
                }

                return parseRemoteCallOrAsyncSendActionRhs(solution.tokenKind, expression, isRhsExpr, rightArrow);
        }

        return parseRemoteCallOrAsyncSendEnd(peek().kind, expression, rightArrow, name);
    }

    private STNode parseRemoteCallOrAsyncSendEnd(SyntaxKind nextTokenKind, STNode expression, STNode rightArrow,
                                                 STNode name) {
        switch (nextTokenKind) {
            case OPEN_PAREN_TOKEN:
                return parseRemoteMethodCallAction(expression, rightArrow, name);
            case SEMICOLON_TOKEN:
                return parseAsyncSendAction(expression, rightArrow, name);
            default:
                STToken token = peek();
                Solution solution =
                        recover(token, ParserRuleContext.REMOTE_CALL_OR_ASYNC_SEND_END, expression, rightArrow, name);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseRemoteCallOrAsyncSendEnd(solution.tokenKind, expression, rightArrow, name);
        }
    }

    /**
     * Parse default keyword.
     *
     * @return default keyword node
     */
    private STNode parseDefaultKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.DEFAULT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.DEFAULT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    private STNode parseAsyncSendAction(STNode expression, STNode rightArrow, STNode peerWorker) {
        return STNodeFactory.createAsyncSendActionNode(expression, rightArrow, peerWorker);
    }

    private STNode parseRemoteMethodCallAction(STNode expression, STNode rightArrow, STNode name) {
        STNode openParenToken = parseOpenParenthesis(ParserRuleContext.ARG_LIST_START);
        STNode arguments = parseArgsList();
        STNode closeParenToken = parseCloseParenthesis();
        return STNodeFactory.createRemoteMethodCallActionNode(expression, rightArrow, name, openParenToken, arguments,
                closeParenToken);
    }

    /**
     * Parse right arrow (<code>-></code>) token.
     *
     * @return Parsed node
     */
    private STNode parseRightArrow() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.RIGHT_ARROW_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(nextToken, ParserRuleContext.RIGHT_ARROW);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse parameterized type descriptor.
     * parameterized-type-descriptor := map type-parameter | future type-parameter | typedesc type-parameter
     *
     * @return Parsed node
     */
    private STNode parseParameterizedTypeDescriptor() {
        STNode parameterizedTypeKeyword = parseParameterizedTypeKeyword();
        STNode ltToken = parseLTToken();
        STNode typeNode = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_ANGLE_BRACKETS);
        STNode gtToken = parseGTToken();
        return STNodeFactory.createParameterizedTypeDescriptorNode(parameterizedTypeKeyword, ltToken, typeNode,
                gtToken);
    }

    /**
     * Parse <code>map</code> or <code>future</code> keyword token.
     *
     * @return Parsed node
     */
    private STNode parseParameterizedTypeKeyword() {
        STToken nextToken = peek();
        switch (nextToken.kind) {
            case MAP_KEYWORD: // map type desc
            case FUTURE_KEYWORD: // future type desc
                return consume();
            default:
                Solution sol = recover(nextToken, ParserRuleContext.PARAMETERIZED_TYPE);
                return sol.recoveredNode;
        }
    }

    /**
     * Parse <code> < </code> token.
     *
     * @return Parsed node
     */
    private STNode parseGTToken() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.GT_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(nextToken, ParserRuleContext.GT);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse <code> > </code> token.
     *
     * @return Parsed node
     */
    private STNode parseLTToken() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.LT_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(nextToken, ParserRuleContext.LT);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse nil literal. Here nil literal is only referred to ( ).
     *
     * @return Parsed node
     */
    private STNode parseNilLiteral() {
        startContext(ParserRuleContext.NIL_LITERAL);
        STNode openParenthesisToken = parseOpenParenthesis(ParserRuleContext.OPEN_PARENTHESIS);
        STNode closeParenthesisToken = parseCloseParenthesis();
        endContext();
        return STNodeFactory.createNilLiteralNode(openParenthesisToken, closeParenthesisToken);
    }

    /**
     * Parse annotation declaration, given the qualifier.
     *
     * @param metadata Metadata
     * @param qualifier Qualifier that precedes the listener declaration
     * @param constKeyword Const keyword
     * @return Parsed node
     */
    private STNode parseAnnotationDeclaration(STNode metadata, STNode qualifier, STNode constKeyword) {
        startContext(ParserRuleContext.ANNOTATION_DECL);
        STNode annotationKeyword = parseAnnotationKeyword();
        STNode annotDecl = parseAnnotationDeclFromType(metadata, qualifier, constKeyword, annotationKeyword);
        endContext();
        return annotDecl;
    }

    /**
     * Parse annotation keyword.
     *
     * @return Parsed node
     */
    private STNode parseAnnotationKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ANNOTATION_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ANNOTATION_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse the components that follows after the annotation keyword of a annotation declaration.
     *
     * @param metadata Metadata
     * @param qualifier Qualifier that precedes the constant decl
     * @param constKeyword Const keyword
     * @param annotationKeyword
     * @return Parsed node
     */
    private STNode parseAnnotationDeclFromType(STNode metadata, STNode qualifier, STNode constKeyword,
                                               STNode annotationKeyword) {
        STToken nextToken = peek();
        return parseAnnotationDeclFromType(nextToken.kind, metadata, qualifier, constKeyword, annotationKeyword);
    }

    private STNode parseAnnotationDeclFromType(SyntaxKind nextTokenKind, STNode metadata, STNode qualifier,
                                               STNode constKeyword, STNode annotationKeyword) {
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN:
                return parseAnnotationDeclWithOptionalType(metadata, qualifier, constKeyword, annotationKeyword);
            default:
                if (isTypeStartingToken(nextTokenKind)) {
                    break;
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.ANNOT_DECL_OPTIONAL_TYPE, metadata, qualifier,
                        constKeyword, annotationKeyword);
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseAnnotationDeclFromType(solution.tokenKind, metadata, qualifier, constKeyword,
                        annotationKeyword);
        }

        STNode typeDesc = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_ANNOTATION_DECL);
        STNode annotTag = parseAnnotationTag();
        return parseAnnotationDeclAttachPoints(metadata, qualifier, constKeyword, annotationKeyword, typeDesc,
                annotTag);
    }

    /**
     * Parse annotation tag.
     * <p>
     * <code>annot-tag := identifier</code>
     *
     * @return
     */
    private STNode parseAnnotationTag() {
        STToken token = peek();
        if (token.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(peek(), ParserRuleContext.ANNOTATION_TAG);
            return sol.recoveredNode;
        }
    }

    private STNode parseAnnotationDeclWithOptionalType(STNode metadata, STNode qualifier, STNode constKeyword,
                                                       STNode annotationKeyword) {
        // We come here if the type name also and identifier.
        // However, if it is a qualified identifier, then it has to be the type-desc.
        STNode typeDescOrAnnotTag = parseQualifiedIdentifier(ParserRuleContext.ANNOT_DECL_OPTIONAL_TYPE);
        if (typeDescOrAnnotTag.kind == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
            STNode annotTag = parseAnnotationTag();
            return parseAnnotationDeclAttachPoints(metadata, qualifier, constKeyword, annotationKeyword,
                    typeDescOrAnnotTag, annotTag);
        }

        // an simple identifier
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.IDENTIFIER_TOKEN || isValidTypeContinuationToken(nextToken)) {
            STNode typeDesc = parseComplexTypeDescriptor(typeDescOrAnnotTag,
                    ParserRuleContext.TYPE_DESC_IN_ANNOTATION_DECL, false);
            STNode annotTag = parseAnnotationTag();
            return parseAnnotationDeclAttachPoints(metadata, qualifier, constKeyword, annotationKeyword, typeDesc,
                    annotTag);
        }

        STNode annotTag = ((STSimpleNameReferenceNode) typeDescOrAnnotTag).name;
        return parseAnnotationDeclRhs(metadata, qualifier, constKeyword, annotationKeyword, annotTag);
    }

    /**
     * Parse the component that follows the first identifier in an annotation decl. The identifier
     * can be either the type-name (a user defined type) or the annot-tag, where the type-name
     * is not present.
     *
     * @param metadata Metadata
     * @param qualifier Qualifier that precedes the annotation decl
     * @param constKeyword Const keyword
     * @param annotationKeyword Annotation keyword
     * @param typeDescOrAnnotTag Identifier that follows the annotation-keyword
     * @return Parsed node
     */
    private STNode parseAnnotationDeclRhs(STNode metadata, STNode qualifier, STNode constKeyword,
                                          STNode annotationKeyword, STNode typeDescOrAnnotTag) {
        STToken token = peek();
        return parseAnnotationDeclRhs(token.kind, metadata, qualifier, constKeyword, annotationKeyword,
                typeDescOrAnnotTag);
    }

    private STNode parseAnnotationDeclRhs(SyntaxKind nextTokenKind, STNode metadata, STNode qualifier,
                                          STNode constKeyword, STNode annotationKeyword, STNode typeDescOrAnnotTag) {
        STNode typeDesc;
        STNode annotTag;
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN:
                typeDesc = typeDescOrAnnotTag;
                annotTag = parseAnnotationTag();
                break;
            case SEMICOLON_TOKEN:
            case ON_KEYWORD:
                typeDesc = STNodeFactory.createEmptyNode();
                annotTag = typeDescOrAnnotTag;
                break;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.ANNOT_DECL_RHS, metadata, qualifier, constKeyword,
                        annotationKeyword, typeDescOrAnnotTag);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseAnnotationDeclRhs(solution.tokenKind, metadata, qualifier, constKeyword, annotationKeyword,
                        typeDescOrAnnotTag);
        }

        return parseAnnotationDeclAttachPoints(metadata, qualifier, constKeyword, annotationKeyword, typeDesc,
                annotTag);
    }

    private STNode parseAnnotationDeclAttachPoints(STNode metadata, STNode qualifier, STNode constKeyword,
                                                   STNode annotationKeyword, STNode typeDesc, STNode annotTag) {
        STToken nextToken = peek();
        return parseAnnotationDeclAttachPoints(nextToken.kind, metadata, qualifier, constKeyword, annotationKeyword,
                typeDesc, annotTag);

    }

    private STNode parseAnnotationDeclAttachPoints(SyntaxKind nextTokenKind, STNode metadata, STNode qualifier,
                                                   STNode constKeyword, STNode annotationKeyword, STNode typeDesc,
                                                   STNode annotTag) {
        STNode onKeyword;
        STNode attachPoints;
        switch (nextTokenKind) {
            case SEMICOLON_TOKEN:
                onKeyword = STNodeFactory.createEmptyNode();
                attachPoints = STNodeFactory.createEmptyNodeList();
                break;
            case ON_KEYWORD:
                onKeyword = parseOnKeyword();
                attachPoints = parseAnnotationAttachPoints();
                onKeyword = cloneWithDiagnosticIfListEmpty(attachPoints, onKeyword,
                        DiagnosticErrorCode.ERROR_MISSING_ANNOTATION_ATTACH_POINT);
                break;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.ANNOT_OPTIONAL_ATTACH_POINTS, metadata, qualifier,
                        constKeyword, annotationKeyword, typeDesc, annotTag);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseAnnotationDeclAttachPoints(solution.tokenKind, metadata, qualifier, constKeyword,
                        annotationKeyword, typeDesc, annotTag);
        }

        STNode semicolonToken = parseSemicolon();
        return STNodeFactory.createAnnotationDeclarationNode(metadata, qualifier, constKeyword, annotationKeyword,
                typeDesc, annotTag, onKeyword, attachPoints, semicolonToken);
    }

    /**
     * Parse annotation attach points.
     * <p>
     * <code>
     * annot-attach-points := annot-attach-point (, annot-attach-point)*
     * <br/><br/>
     * annot-attach-point := dual-attach-point | source-only-attach-point
     * <br/><br/>
     * dual-attach-point := [source] dual-attach-point-ident
     * <br/><br/>
     * dual-attach-point-ident :=
     *     [object] type
     *     | [object|resource] function
     *     | parameter
     *     | return
     *     | service
     *     | [object|record] field
     * <br/><br/>
     * source-only-attach-point := source source-only-attach-point-ident
     * <br/><br/>
     * source-only-attach-point-ident :=
     *     annotation
     *     | external
     *     | var
     *     | const
     *     | listener
     *     | worker
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseAnnotationAttachPoints() {
        startContext(ParserRuleContext.ANNOT_ATTACH_POINTS_LIST);
        List<STNode> attachPoints = new ArrayList<>();

        STToken nextToken = peek();
        if (isEndAnnotAttachPointList(nextToken.kind)) {
            endContext();
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first attach-point, that has no leading comma
        STNode attachPoint = parseAnnotationAttachPoint();
        attachPoints.add(attachPoint);

        // Parse the remaining attach-points
        nextToken = peek();
        STNode leadingComma;
        while (!isEndAnnotAttachPointList(nextToken.kind)) {
            leadingComma = parseAttachPointEnd();
            if (leadingComma == null) {
                break;
            }
            attachPoints.add(leadingComma);

            // Parse attach point. Null represents the end of attach-points.
            attachPoint = parseAnnotationAttachPoint();
            if (attachPoint == null) {
                attachPoint = SyntaxErrors.createMissingTokenWithDiagnostics(SyntaxKind.IDENTIFIER_TOKEN,
                        DiagnosticErrorCode.ERROR_MISSING_ANNOTATION_ATTACH_POINT);
                attachPoints.add(attachPoint);
                break;
            }

            attachPoints.add(attachPoint);
            nextToken = peek();
        }

        endContext();
        return STNodeFactory.createNodeList(attachPoints);
    }

    /**
     * Parse annotation attach point end.
     *
     * @return Parsed node
     */
    private STNode parseAttachPointEnd() {
        STToken nextToken = peek();
        return parseAttachPointEnd(nextToken.kind);
    }

    private STNode parseAttachPointEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case SEMICOLON_TOKEN:
                // null represents the end of attach points.
                return null;
            case COMMA_TOKEN:
                return consume();
            default:
                Solution sol = recover(peek(), ParserRuleContext.ATTACH_POINT_END);
                if (sol.action == Action.REMOVE) {
                    return sol.recoveredNode;
                }

                return sol.tokenKind == SyntaxKind.COMMA_TOKEN ? sol.recoveredNode : null;
        }
    }

    private boolean isEndAnnotAttachPointList(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case EOF_TOKEN:
            case SEMICOLON_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse annotation attach point.
     *
     * @return Parsed node
     */
    private STNode parseAnnotationAttachPoint() {
        return parseAnnotationAttachPoint(peek().kind);
    }

    private STNode parseAnnotationAttachPoint(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case EOF_TOKEN:
                return null;

            // These are source only annotations, but without the source keyword.
            case ANNOTATION_KEYWORD:
            case EXTERNAL_KEYWORD:
            case VAR_KEYWORD:
            case CONST_KEYWORD:
            case LISTENER_KEYWORD:
            case WORKER_KEYWORD:
                // fall through

            case SOURCE_KEYWORD:
                STNode sourceKeyword = parseSourceKeyword();
                return parseAttachPointIdent(sourceKeyword);

            // Dual attach points
            case OBJECT_KEYWORD:
            case TYPE_KEYWORD:
            case RESOURCE_KEYWORD:
            case FUNCTION_KEYWORD:
            case PARAMETER_KEYWORD:
            case RETURN_KEYWORD:
            case SERVICE_KEYWORD:
            case FIELD_KEYWORD:
            case RECORD_KEYWORD:
                sourceKeyword = STNodeFactory.createEmptyNode();
                STNode firstIdent = consume();
                return parseDualAttachPointIdent(sourceKeyword, firstIdent);
            default:
                Solution solution = recover(peek(), ParserRuleContext.ATTACH_POINT);
                return solution.recoveredNode;
        }
    }

    /**
     * Parse source keyword.
     *
     * @return Parsed node
     */
    private STNode parseSourceKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.SOURCE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.SOURCE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse attach point ident gievn.
     * <p>
     * <code>
     * source-only-attach-point-ident := annotation | external | var | const | listener | worker
     * <br/><br/>
     * dual-attach-point-ident := [object] type | [object|resource] function | parameter
     *                            | return | service | [object|record] field
     * </code>
     *
     * @param sourceKeyword Source keyword
     * @return Parsed node
     */
    private STNode parseAttachPointIdent(STNode sourceKeyword) {
        return parseAttachPointIdent(peek().kind, sourceKeyword);
    }

    private STNode parseAttachPointIdent(SyntaxKind nextTokenKind, STNode sourceKeyword) {
        switch (nextTokenKind) {
            case ANNOTATION_KEYWORD:
            case EXTERNAL_KEYWORD:
            case VAR_KEYWORD:
            case CONST_KEYWORD:
            case LISTENER_KEYWORD:
            case WORKER_KEYWORD:
                STNode firstIdent = consume();
                STNode secondIdent = STNodeFactory.createEmptyNode();
                return STNodeFactory.createAnnotationAttachPointNode(sourceKeyword, firstIdent, secondIdent);
            case OBJECT_KEYWORD:
            case RESOURCE_KEYWORD:
            case RECORD_KEYWORD:
            case TYPE_KEYWORD:
            case FUNCTION_KEYWORD:
            case PARAMETER_KEYWORD:
            case RETURN_KEYWORD:
            case SERVICE_KEYWORD:
            case FIELD_KEYWORD:
                firstIdent = consume();
                return parseDualAttachPointIdent(sourceKeyword, firstIdent);
            default:
                Solution solution = recover(peek(), ParserRuleContext.ATTACH_POINT_IDENT, sourceKeyword);
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                firstIdent = solution.recoveredNode;
                return parseDualAttachPointIdent(sourceKeyword, firstIdent);
        }
    }

    /**
     * Parse dual-attach-point ident.
     *
     * @param sourceKeyword Source keyword
     * @param firstIdent first part of the dual attach-point
     * @return Parsed node
     */
    private STNode parseDualAttachPointIdent(STNode sourceKeyword, STNode firstIdent) {
        STNode secondIdent;
        switch (firstIdent.kind) {
            case OBJECT_KEYWORD:
                secondIdent = parseIdentAfterObjectIdent();
                break;
            case RESOURCE_KEYWORD:
                secondIdent = parseFunctionIdent();
                break;
            case RECORD_KEYWORD:
                secondIdent = parseFieldIdent();
                break;
            case TYPE_KEYWORD:
            case FUNCTION_KEYWORD:
            case PARAMETER_KEYWORD:
            case RETURN_KEYWORD:
            case SERVICE_KEYWORD:
            case FIELD_KEYWORD:
            default: // default case should never be reached.
                secondIdent = STNodeFactory.createEmptyNode();
                break;
        }

        return STNodeFactory.createAnnotationAttachPointNode(sourceKeyword, firstIdent, secondIdent);
    }

    /**
     * Parse the idents that are supported after object-ident.
     *
     * @return Parsed node
     */
    private STNode parseIdentAfterObjectIdent() {
        STToken token = peek();
        switch (token.kind) {
            case TYPE_KEYWORD:
            case FUNCTION_KEYWORD:
            case FIELD_KEYWORD:
                return consume();
            default:
                Solution sol = recover(token, ParserRuleContext.IDENT_AFTER_OBJECT_IDENT);
                return sol.recoveredNode;
        }
    }

    /**
     * Parse function ident.
     *
     * @return Parsed node
     */
    private STNode parseFunctionIdent() {
        STToken token = peek();
        if (token.kind == SyntaxKind.FUNCTION_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FUNCTION_IDENT);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse field ident.
     *
     * @return Parsed node
     */
    private STNode parseFieldIdent() {
        STToken token = peek();
        if (token.kind == SyntaxKind.FIELD_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FIELD_IDENT);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse XML namespace declaration.
     * <p>
     * <code>xmlns-decl := xmlns xml-namespace-uri [ as xml-namespace-prefix ] ;
     * <br/>
     * xml-namespace-uri := simple-const-expr
     * <br/>
     * xml-namespace-prefix := identifier
     * </code>
     *
     * @return
     */
    private STNode parseXMLNamespaceDeclaration(boolean isModuleVar) {
        startContext(ParserRuleContext.XML_NAMESPACE_DECLARATION);
        STNode xmlnsKeyword = parseXMLNSKeyword();

        STNode namespaceUri = parseSimpleConstExpr();
        while (!isValidXMLNameSpaceURI(namespaceUri)) {
            xmlnsKeyword = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(xmlnsKeyword, namespaceUri,
                    DiagnosticErrorCode.ERROR_INVALID_XML_NAMESPACE_URI);
            namespaceUri = parseSimpleConstExpr();
        }

        STNode xmlnsDecl = parseXMLDeclRhs(xmlnsKeyword, namespaceUri, isModuleVar);
        endContext();
        return xmlnsDecl;
    }

    /**
     * Parse xmlns keyword.
     *
     * @return Parsed node
     */
    private STNode parseXMLNSKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.XMLNS_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.XMLNS_KEYWORD);
            return sol.recoveredNode;
        }
    }

    private boolean isValidXMLNameSpaceURI(STNode expr) {
        switch (expr.kind) {
            case STRING_LITERAL:
            case QUALIFIED_NAME_REFERENCE:
            case SIMPLE_NAME_REFERENCE:
                return true;
            case IDENTIFIER_TOKEN:
            default:
                return false;
        }
    }

    private STNode parseSimpleConstExpr() {
        startContext(ParserRuleContext.CONSTANT_EXPRESSION);
        STNode expr = parseSimpleConstExprInternal();
        endContext();
        return expr;
    }

    private STNode parseSimpleConstExprInternal() {
        STToken nextToken = peek();
        return parseConstExprInternal(nextToken.kind);
    }

    /**
     * Parse constants expr.
     *
     * @return Parsed node
     */
    private STNode parseConstExprInternal(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case STRING_LITERAL:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case NULL_KEYWORD:
                return parseBasicLiteral();
            case IDENTIFIER_TOKEN:
                return parseQualifiedIdentifier(ParserRuleContext.VARIABLE_REF);
            case PLUS_TOKEN:
            case MINUS_TOKEN:
                return parseSignedIntOrFloat();
            case OPEN_PAREN_TOKEN:
                return parseNilLiteral();
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.CONSTANT_EXPRESSION_START);

                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                // Here we assume inserted token is always an identifier token
                return STNodeFactory.createSimpleNameReferenceNode(solution.recoveredNode);
        }
    }

    /**
     * Parse the portion after the namsepsace-uri of an XML declaration.
     *
     * @param xmlnsKeyword XMLNS keyword
     * @param namespaceUri Namespace URI
     * @return Parsed node
     */
    private STNode parseXMLDeclRhs(STNode xmlnsKeyword, STNode namespaceUri, boolean isModuleVar) {
        return parseXMLDeclRhs(peek().kind, xmlnsKeyword, namespaceUri, isModuleVar);
    }

    private STNode parseXMLDeclRhs(SyntaxKind nextTokenKind, STNode xmlnsKeyword, STNode namespaceUri,
                                   boolean isModuleVar) {
        STNode asKeyword = STNodeFactory.createEmptyNode();
        STNode namespacePrefix = STNodeFactory.createEmptyNode();

        switch (nextTokenKind) {
            case AS_KEYWORD:
                asKeyword = parseAsKeyword();
                namespacePrefix = parseNamespacePrefix();
                break;
            case SEMICOLON_TOKEN:
                break;
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.XML_NAMESPACE_PREFIX_DECL, xmlnsKeyword,
                        namespaceUri, isModuleVar);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseXMLDeclRhs(solution.tokenKind, xmlnsKeyword, namespaceUri, isModuleVar);
        }
        STNode semicolon = parseSemicolon();
        if (isModuleVar) {
            return STNodeFactory.createModuleXMLNamespaceDeclarationNode(xmlnsKeyword, namespaceUri, asKeyword,
                    namespacePrefix, semicolon);
        }
        return STNodeFactory.createXMLNamespaceDeclarationNode(xmlnsKeyword, namespaceUri, asKeyword, namespacePrefix,
                semicolon);
    }

    /**
     * Parse import prefix.
     *
     * @return Parsed node
     */
    private STNode parseNamespacePrefix() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(peek(), ParserRuleContext.NAMESPACE_PREFIX);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse named worker declaration.
     * <p>
     * <code>named-worker-decl := [annots] worker worker-name return-type-descriptor { sequence-stmt }</code>
     *
     * @param annots Annotations attached to the worker decl
     * @return Parsed node
     */
    private STNode parseNamedWorkerDeclaration(STNode annots) {
        startContext(ParserRuleContext.NAMED_WORKER_DECL);
        STNode workerKeyword = parseWorkerKeyword();
        STNode workerName = parseWorkerName();
        STNode returnTypeDesc = parseReturnTypeDescriptor();
        STNode workerBody = parseBlockNode();
        endContext();
        return STNodeFactory.createNamedWorkerDeclarationNode(annots, workerKeyword, workerName, returnTypeDesc,
                workerBody);
    }

    private STNode parseReturnTypeDescriptor() {
        // If the return type is not present, simply return
        STToken token = peek();
        if (token.kind != SyntaxKind.RETURNS_KEYWORD) {
            return STNodeFactory.createEmptyNode();
        }

        STNode returnsKeyword = consume();
        STNode annot = parseOptionalAnnotations();
        STNode type = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_RETURN_TYPE_DESC);
        return STNodeFactory.createReturnTypeDescriptorNode(returnsKeyword, annot, type);
    }

    /**
     * Parse worker keyword.
     *
     * @return Parsed node
     */
    private STNode parseWorkerKeyword() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.WORKER_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(peek(), ParserRuleContext.WORKER_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse worker name.
     * <p>
     * <code>worker-name := identifier</code>
     *
     * @return Parsed node
     */
    private STNode parseWorkerName() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.IDENTIFIER_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(peek(), ParserRuleContext.WORKER_NAME);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse lock statement.
     * <code>lock-stmt := lock block-stmt ;</code>
     *
     * @return Lock statement
     */
    private STNode parseLockStatement() {
        startContext(ParserRuleContext.LOCK_STMT);
        STNode lockKeyword = parseLockKeyword();
        STNode blockStatement = parseBlockNode();
        endContext();
        return STNodeFactory.createLockStatementNode(lockKeyword, blockStatement);
    }

    /**
     * Parse lock-keyword.
     *
     * @return lock-keyword node
     */
    private STNode parseLockKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.LOCK_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.LOCK_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse union type descriptor.
     * union-type-descriptor := type-descriptor | type-descriptor
     *
     * @param leftTypeDesc Type desc in the LHS os the union type desc.
     * @param context Current context.
     * @return parsed union type desc node
     */
    private STNode parseUnionTypeDescriptor(STNode leftTypeDesc, ParserRuleContext context,
                                            boolean isTypedBindingPattern) {
        STNode pipeToken = parsePipeToken();
        STNode rightTypeDesc = parseTypeDescriptor(context, isTypedBindingPattern, false);

        return createUnionTypeDesc(leftTypeDesc, pipeToken, rightTypeDesc);
    }

    private STNode createUnionTypeDesc(STNode leftTypeDesc, STNode pipeToken, STNode rightTypeDesc) {
        leftTypeDesc = validateForUsageOfVar(leftTypeDesc);
        rightTypeDesc = validateForUsageOfVar(rightTypeDesc);
        return STNodeFactory.createUnionTypeDescriptorNode(leftTypeDesc, pipeToken, rightTypeDesc);
    }

    /**
     * Parse pipe token.
     *
     * @return parsed pipe token node
     */
    private STNode parsePipeToken() {
        STToken token = peek();
        if (token.kind == SyntaxKind.PIPE_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.PIPE);
            return sol.recoveredNode;
        }
    }

    private boolean isTypeStartingToken(SyntaxKind nodeKind) {
        switch (nodeKind) {
            case IDENTIFIER_TOKEN:
            case SERVICE_KEYWORD:
            case RECORD_KEYWORD:
            case OBJECT_KEYWORD:
            case ABSTRACT_KEYWORD:
            case CLIENT_KEYWORD:
            case OPEN_PAREN_TOKEN: // nil type descriptor '()'
            case MAP_KEYWORD: // map type desc
            case FUTURE_KEYWORD: // future type desc
            case TYPEDESC_KEYWORD: // typedesc type desc
            case ERROR_KEYWORD: // error type desc
            case STREAM_KEYWORD: // stream type desc
            case TABLE_KEYWORD: // table type
            case FUNCTION_KEYWORD:
            case OPEN_BRACKET_TOKEN:
            case DISTINCT_KEYWORD:
                return true;
            default:
                if (isSingletonTypeDescStart(nodeKind, true)) {
                    return true;
                }
                return isSimpleType(nodeKind);
        }
    }

    static boolean isSimpleType(SyntaxKind nodeKind) {
        switch (nodeKind) {
            case INT_KEYWORD:
            case FLOAT_KEYWORD:
            case DECIMAL_KEYWORD:
            case BOOLEAN_KEYWORD:
            case STRING_KEYWORD:
            case BYTE_KEYWORD:
            case XML_KEYWORD:
            case JSON_KEYWORD:
            case HANDLE_KEYWORD:
            case ANY_KEYWORD:
            case ANYDATA_KEYWORD:
            case NEVER_KEYWORD:
            case SERVICE_KEYWORD:
            case VAR_KEYWORD:
            case ERROR_KEYWORD: // This is for the recovery. <code>error a;</code> scenario recovered here.
            case STREAM_KEYWORD: // This is for recovery logic. <code>stream a;</code> scenario recovered here.
            case TYPEDESC_KEYWORD: // This is for recovery logic. <code>typedesc a;</code> scenario recovered here.
            case READONLY_KEYWORD:
            case DISTINCT_KEYWORD:
                return true;
            case TYPE_DESC:
                // This is a special case. TYPE_DESC is only return from
                // error recovery. when a type is missing. Hence we treat it as
                // a simple type
                return true;
            default:
                return false;
        }
    }

    private SyntaxKind getTypeSyntaxKind(SyntaxKind typeKeyword) {
        switch (typeKeyword) {
            case INT_KEYWORD:
                return SyntaxKind.INT_TYPE_DESC;
            case FLOAT_KEYWORD:
                return SyntaxKind.FLOAT_TYPE_DESC;
            case DECIMAL_KEYWORD:
                return SyntaxKind.DECIMAL_TYPE_DESC;
            case BOOLEAN_KEYWORD:
                return SyntaxKind.BOOLEAN_TYPE_DESC;
            case STRING_KEYWORD:
                return SyntaxKind.STRING_TYPE_DESC;
            case BYTE_KEYWORD:
                return SyntaxKind.BYTE_TYPE_DESC;
            case XML_KEYWORD:
                return SyntaxKind.XML_TYPE_DESC;
            case JSON_KEYWORD:
                return SyntaxKind.JSON_TYPE_DESC;
            case HANDLE_KEYWORD:
                return SyntaxKind.HANDLE_TYPE_DESC;
            case ANY_KEYWORD:
                return SyntaxKind.ANY_TYPE_DESC;
            case ANYDATA_KEYWORD:
                return SyntaxKind.ANYDATA_TYPE_DESC;
            case READONLY_KEYWORD:
                return SyntaxKind.READONLY_TYPE_DESC;
            case NEVER_KEYWORD:
                return SyntaxKind.NEVER_TYPE_DESC;
            case SERVICE_KEYWORD:
                return SyntaxKind.SERVICE_TYPE_DESC;
            case VAR_KEYWORD:
                return SyntaxKind.VAR_TYPE_DESC;
            case ERROR_KEYWORD:
                return SyntaxKind.ERROR_TYPE_DESC;
            default:
                return SyntaxKind.TYPE_DESC;
        }
    }

    /**
     * Parse fork-keyword.
     *
     * @return Fork-keyword node
     */
    private STNode parseForkKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.FORK_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FORK_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse fork statement.
     * <code>fork-stmt := fork { named-worker-decl+ }</code>
     *
     * @return Fork statement
     */
    private STNode parseForkStatement() {
        startContext(ParserRuleContext.FORK_STMT);
        STNode forkKeyword = parseForkKeyword();
        STNode openBrace = parseOpenBrace();

        // Parse named-worker declarations
        ArrayList<STNode> workers = new ArrayList<>();
        while (!isEndOfStatements()) {
            STNode stmt = parseStatement();
            if (stmt == null) {
                break;
            }

            switch (stmt.kind) {
                case NAMED_WORKER_DECLARATION:
                    workers.add(stmt);
                    break;
                default:
                    // TODO need to check whether we've already added the same diagnostics before
                    // TODO We need to avoid repetitive diagnostics of same kind
                    // I think we need a method to check whether a node has a particular diagnostic
                    if (workers.isEmpty()) {
                        openBrace = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(openBrace, stmt,
                                DiagnosticErrorCode.ERROR_ONLY_NAMED_WORKERS_ALLOWED_HERE);
                    } else {
                        updateLastNodeInListWithInvalidNode(workers, stmt,
                                DiagnosticErrorCode.ERROR_ONLY_NAMED_WORKERS_ALLOWED_HERE);
                    }
            }
        }

        STNode namedWorkerDeclarations = STNodeFactory.createNodeList(workers);
        STNode closeBrace = parseCloseBrace();
        endContext();

        STNode forkStmt =
                STNodeFactory.createForkStatementNode(forkKeyword, openBrace, namedWorkerDeclarations, closeBrace);
        if (isNodeListEmpty(namedWorkerDeclarations)) {
            return SyntaxErrors.addDiagnostic(forkStmt,
                    DiagnosticErrorCode.ERROR_MISSING_NAMED_WORKER_DECLARATION_IN_FORK_STMT);
        }

        return forkStmt;
    }

    /**
     * Parse trap expression.
     * <p>
     * <code>
     * trap-expr := trap expression
     * </code>
     *
     * @param allowActions Allow actions
     * @param isRhsExpr Whether this is a RHS expression or not
     * @return Trap expression node
     */
    private STNode parseTrapExpression(boolean isRhsExpr, boolean allowActions, boolean isInConditionalExpr) {
        STNode trapKeyword = parseTrapKeyword();
        STNode expr =
                parseExpression(OperatorPrecedence.EXPRESSION_ACTION, isRhsExpr, allowActions, isInConditionalExpr);
        if (isAction(expr)) {
            return STNodeFactory.createTrapExpressionNode(SyntaxKind.TRAP_ACTION, trapKeyword, expr);
        }

        return STNodeFactory.createTrapExpressionNode(SyntaxKind.TRAP_EXPRESSION, trapKeyword, expr);
    }

    /**
     * Parse trap-keyword.
     *
     * @return Trap-keyword node
     */
    private STNode parseTrapKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.TRAP_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.TRAP_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse list constructor expression.
     * <p>
     * <code>
     * list-constructor-expr := [ [ expr-list ] ]
     * <br/>
     * expr-list := expression (, expression)*
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseListConstructorExpr() {
        startContext(ParserRuleContext.LIST_CONSTRUCTOR);
        STNode openBracket = parseOpenBracket();
        STNode expressions = parseOptionalExpressionsList();
        STNode closeBracket = parseCloseBracket();
        endContext();
        return STNodeFactory.createListConstructorExpressionNode(openBracket, expressions, closeBracket);
    }

    /**
     * Parse optional expression list.
     *
     * @return Parsed node
     */
    private STNode parseOptionalExpressionsList() {
        List<STNode> expressions = new ArrayList<>();
        if (isEndOfListConstructor(peek().kind)) {
            return STNodeFactory.createEmptyNodeList();
        }

        STNode expr = parseExpression();
        expressions.add(expr);
        return parseOptionalExpressionsList(expressions);
    }

    private STNode parseOptionalExpressionsList(List<STNode> expressions) {
        // Parse the remaining expressions
        STNode listConstructorMemberEnd;
        while (!isEndOfListConstructor(peek().kind)) {

            listConstructorMemberEnd = parseListConstructorMemberEnd();
            if (listConstructorMemberEnd == null) {
                break;
            }
            expressions.add(listConstructorMemberEnd);

            STNode expr = parseExpression();
            expressions.add(expr);
        }

        return STNodeFactory.createNodeList(expressions);
    }

    private boolean isEndOfListConstructor(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACKET_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode parseListConstructorMemberEnd() {
        return parseListConstructorMemberEnd(peek().kind);
    }

    private STNode parseListConstructorMemberEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACKET_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.LIST_CONSTRUCTOR_MEMBER_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }
                return parseListConstructorMemberEnd(solution.tokenKind);
        }
    }

    /**
     * Parse foreach statement.
     * <code>foreach-stmt := foreach typed-binding-pattern in action-or-expr block-stmt</code>
     *
     * @return foreach statement
     */
    private STNode parseForEachStatement() {
        startContext(ParserRuleContext.FOREACH_STMT);
        STNode forEachKeyword = parseForEachKeyword();
        STNode typedBindingPattern = parseTypedBindingPattern(ParserRuleContext.FOREACH_STMT);
        STNode inKeyword = parseInKeyword();
        STNode actionOrExpr = parseActionOrExpression();
        STNode blockStatement = parseBlockNode();
        endContext();
        return STNodeFactory.createForEachStatementNode(forEachKeyword, typedBindingPattern, inKeyword, actionOrExpr,
                blockStatement);
    }

    /**
     * Parse foreach-keyword.
     *
     * @return ForEach-keyword node
     */
    private STNode parseForEachKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.FOREACH_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FOREACH_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse in-keyword.
     *
     * @return In-keyword node
     */
    private STNode parseInKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.IN_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.IN_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse type cast expression.
     * <p>
     * <code>
     * type-cast-expr := < type-cast-param > expression
     * <br/>
     * type-cast-param := [annots] type-descriptor | annots
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseTypeCastExpr(boolean isRhsExpr, boolean allowActions, boolean isInConditionalExpr) {
        startContext(ParserRuleContext.TYPE_CAST);
        STNode ltToken = parseLTToken();
        STNode typeCastParam = parseTypeCastParam();
        STNode gtToken = parseGTToken();
        endContext();

        // allow-actions flag is always false, since there will not be any actions
        // within the type-cast-expr, due to the precedence.
        STNode expression =
                parseExpression(OperatorPrecedence.EXPRESSION_ACTION, isRhsExpr, allowActions, isInConditionalExpr);
        return STNodeFactory.createTypeCastExpressionNode(ltToken, typeCastParam, gtToken, expression);
    }

    private STNode parseTypeCastParam() {
        STNode annot;
        STNode type;
        STToken token = peek();

        switch (token.kind) {
            case AT_TOKEN:
                annot = parseOptionalAnnotations();
                token = peek();
                if (isTypeStartingToken(token.kind)) {
                    type = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_ANGLE_BRACKETS);
                } else {
                    type = STNodeFactory.createEmptyNode();
                }
                break;
            default:
                annot = STNodeFactory.createEmptyNode();
                type = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_ANGLE_BRACKETS);
                break;
        }

        return STNodeFactory.createTypeCastParamNode(getAnnotations(annot), type);
    }

    /**
     * Parse table constructor expression.
     * <p>
     * <code>
     * table-constructor-expr-rhs := [ [row-list] ]
     * </code>
     *
     * @param tableKeyword tableKeyword that precedes this rhs
     * @param keySpecifier keySpecifier that precedes this rhs
     * @return Parsed node
     */
    private STNode parseTableConstructorExprRhs(STNode tableKeyword, STNode keySpecifier) {
        switchContext(ParserRuleContext.TABLE_CONSTRUCTOR);
        STNode openBracket = parseOpenBracket();
        STNode rowList = parseRowList();
        STNode closeBracket = parseCloseBracket();
        return STNodeFactory.createTableConstructorExpressionNode(tableKeyword, keySpecifier, openBracket, rowList,
                closeBracket);
    }

    /**
     * Parse table-keyword.
     *
     * @return Table-keyword node
     */
    private STNode parseTableKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.TABLE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.TABLE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse table rows.
     * <p>
     * <code>row-list := [ mapping-constructor-expr (, mapping-constructor-expr)* ]</code>
     *
     * @return Parsed node
     */
    private STNode parseRowList() {
        STToken nextToken = peek();
        // Return an empty list if list is empty
        if (isEndOfTableRowList(nextToken.kind)) {
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first mapping constructor, that has no leading comma
        List<STNode> mappings = new ArrayList<>();
        STNode mapExpr = parseMappingConstructorExpr();
        mappings.add(mapExpr);

        // Parse the remaining mapping constructors
        nextToken = peek();
        STNode rowEnd;
        while (!isEndOfTableRowList(nextToken.kind)) {
            rowEnd = parseTableRowEnd(nextToken.kind);
            if (rowEnd == null) {
                break;
            }

            mappings.add(rowEnd);
            mapExpr = parseMappingConstructorExpr();
            mappings.add(mapExpr);
            nextToken = peek();
        }

        return STNodeFactory.createNodeList(mappings);
    }

    private boolean isEndOfTableRowList(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACKET_TOKEN:
                return true;
            case COMMA_TOKEN:
            case OPEN_BRACE_TOKEN:
                return false;
            default:
                return isEndOfMappingConstructor(tokenKind);
        }
    }

    private STNode parseTableRowEnd() {
        STNode nextToken = peek();
        return parseTableRowEnd(nextToken.kind);
    }

    private STNode parseTableRowEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACKET_TOKEN:
            case EOF_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.TABLE_ROW_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }
                return parseListConstructorMemberEnd(solution.tokenKind);
        }
    }

    /**
     * Parse key specifier.
     * <p>
     * <code>key-specifier := key ( [ field-name (, field-name)* ] )</code>
     *
     * @return Parsed node
     */
    private STNode parseKeySpecifier() {
        startContext(ParserRuleContext.KEY_SPECIFIER);
        STNode keyKeyword = parseKeyKeyword();
        STNode openParen = parseOpenParenthesis(ParserRuleContext.OPEN_PARENTHESIS);
        STNode fieldNames = parseFieldNames();
        STNode closeParen = parseCloseParenthesis();
        endContext();
        return STNodeFactory.createKeySpecifierNode(keyKeyword, openParen, fieldNames, closeParen);
    }

    /**
     * Parse key-keyword.
     *
     * @return Key-keyword node
     */
    private STNode parseKeyKeyword() {
        STToken token = peek();
        if (isKeyKeyword(token)) {
            // this is to treat "key" as a keyword, even if its parsed as an identifier from lexer.
            return getKeyKeyword(consume());
        } else {
            Solution sol = recover(token, ParserRuleContext.KEY_KEYWORD);
            return sol.recoveredNode;
        }
    }

    static boolean isKeyKeyword(STToken token) {
        return token.kind == SyntaxKind.IDENTIFIER_TOKEN && LexerTerminals.KEY.equals(token.text());
    }

    private STNode getKeyKeyword(STToken token) {
        return STNodeFactory.createToken(SyntaxKind.KEY_KEYWORD, token.leadingMinutiae(), token.trailingMinutiae(),
                token.diagnostics());
    }

    /**
     * Parse field names.
     * <p>
     * <code>field-name-list := [ field-name (, field-name)* ]</code>
     *
     * @return Parsed node
     */
    private STNode parseFieldNames() {
        STToken nextToken = peek();
        // Return an empty list if list is empty
        if (isEndOfFieldNamesList(nextToken.kind)) {
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first field name, that has no leading comma
        List<STNode> fieldNames = new ArrayList<>();
        STNode fieldName = parseVariableName();
        fieldNames.add(fieldName);

        // Parse the remaining field names
        nextToken = peek();
        STNode leadingComma;
        while (!isEndOfFieldNamesList(nextToken.kind)) {
            leadingComma = parseComma();
            fieldNames.add(leadingComma);
            fieldName = parseVariableName();
            fieldNames.add(fieldName);
            nextToken = peek();
        }

        return STNodeFactory.createNodeList(fieldNames);
    }

    private boolean isEndOfFieldNamesList(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case COMMA_TOKEN:
            case IDENTIFIER_TOKEN:
                return false;
            default:
                return true;
        }
    }

    /**
     * Parse error type descriptor.
     * <p>
     * error-type-descriptor := error [error-type-param]
     * error-type-param := < (detail-type-descriptor | inferred-type-descriptor) >
     * detail-type-descriptor := type-descriptor
     * inferred-type-descriptor := *
     * </p>
     *
     * @return Parsed node
     */
    private STNode parseErrorTypeDescriptor() {
        STNode errorKeywordToken = parseErrorKeyword();
        STNode errorTypeParamsNode;
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.LT_TOKEN) {
            errorTypeParamsNode = parseErrorTypeParamsNode();
        } else {
            errorTypeParamsNode = STNodeFactory.createEmptyNode();
        }
        return STNodeFactory.createErrorTypeDescriptorNode(errorKeywordToken, errorTypeParamsNode);
    }

    /**
     * Parse error type param node.
     * <p>
     * error-type-param := < (detail-type-descriptor | inferred-type-descriptor) >
     * detail-type-descriptor := type-descriptor
     * inferred-type-descriptor := *
     * </p>
     *
     * @return Parsed node
     */
    private STNode parseErrorTypeParamsNode() {
        STNode ltToken = parseLTToken();
        STNode parameter;
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.ASTERISK_TOKEN) {
            parameter = consume();
        } else {
            parameter = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_ANGLE_BRACKETS);
        }
        STNode gtToken = parseGTToken();
        return STNodeFactory.createErrorTypeParamsNode(ltToken, parameter, gtToken);
    }

    /**
     * Parse error-keyword.
     *
     * @return Parsed error-keyword node
     */
    private STNode parseErrorKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ERROR_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ERROR_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse typedesc type descriptor.
     * typedesc-type-descriptor := typedesc type-parameter
     *
     * @return Parsed typedesc type node
     */
    private STNode parseTypedescTypeDescriptor() {
        STNode typedescKeywordToken = parseTypedescKeyword();
        STNode typedescTypeParamsNode;
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.LT_TOKEN) {
            typedescTypeParamsNode = parseTypeParameter();
        } else {
            typedescTypeParamsNode = STNodeFactory.createEmptyNode();
        }
        return STNodeFactory.createTypedescTypeDescriptorNode(typedescKeywordToken, typedescTypeParamsNode);
    }

    /**
     * Parse typedesc-keyword.
     *
     * @return Parsed typedesc-keyword node
     */
    private STNode parseTypedescKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.TYPEDESC_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.TYPEDESC_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse stream type descriptor.
     * <p>
     * stream-type-descriptor := stream [stream-type-parameters]
     * stream-type-parameters := < type-descriptor [, type-descriptor]>
     * </p>
     *
     * @return Parsed stream type descriptor node
     */
    private STNode parseStreamTypeDescriptor() {
        STNode streamKeywordToken = parseStreamKeyword();
        STNode streamTypeParamsNode;
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.LT_TOKEN) {
            streamTypeParamsNode = parseStreamTypeParamsNode();
        } else {
            streamTypeParamsNode = STNodeFactory.createEmptyNode();
        }
        return STNodeFactory.createStreamTypeDescriptorNode(streamKeywordToken, streamTypeParamsNode);
    }

    /**
     * Parse xml type descriptor.
     * xml-type-descriptor := xml type-parameter
     *
     * @return Parsed typedesc type node
     */
    private STNode parseXmlTypeDescriptor() {
        STNode xmlKeywordToken = parseXMLKeyword();
        STNode typedescTypeParamsNode;
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.LT_TOKEN) {
            typedescTypeParamsNode = parseTypeParameter();
        } else {
            typedescTypeParamsNode = STNodeFactory.createEmptyNode();
        }
        return STNodeFactory.createXmlTypeDescriptorNode(xmlKeywordToken, typedescTypeParamsNode);
    }

    /**
     * Parse stream type params node.
     * <p>
     * stream-type-parameters := < type-descriptor [, type-descriptor]>
     * </p>
     *
     * @return Parsed stream type params node
     */
    private STNode parseStreamTypeParamsNode() {
        STNode ltToken = parseLTToken();
        startContext(ParserRuleContext.TYPE_DESC_IN_STREAM_TYPE_DESC);
        STNode leftTypeDescNode = parseTypeDescriptorInternal(ParserRuleContext.TYPE_DESC_IN_STREAM_TYPE_DESC, false);
        STNode streamTypedesc = parseStreamTypeParamsNode(ltToken, leftTypeDescNode);
        endContext();
        return streamTypedesc;
    }

    private STNode parseStreamTypeParamsNode(STNode ltToken, STNode leftTypeDescNode) {
        return parseStreamTypeParamsNode(peek().kind, ltToken, leftTypeDescNode);
    }

    private STNode parseStreamTypeParamsNode(SyntaxKind nextTokenKind, STNode ltToken, STNode leftTypeDescNode) {
        STNode commaToken, rightTypeDescNode, gtToken;

        switch (nextTokenKind) {
            case COMMA_TOKEN:
                commaToken = parseComma();
                rightTypeDescNode = parseTypeDescriptorInternal(ParserRuleContext.TYPE_DESC_IN_STREAM_TYPE_DESC, false);
                break;
            case GT_TOKEN:
                commaToken = STNodeFactory.createEmptyNode();
                rightTypeDescNode = STNodeFactory.createEmptyNode();
                break;
            default:
                Solution solution =
                        recover(peek(), ParserRuleContext.STREAM_TYPE_FIRST_PARAM_RHS, ltToken, leftTypeDescNode);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }
                return parseStreamTypeParamsNode(solution.tokenKind, ltToken, leftTypeDescNode);
        }

        gtToken = parseGTToken();
        return STNodeFactory.createStreamTypeParamsNode(ltToken, leftTypeDescNode, commaToken, rightTypeDescNode,
                gtToken);
    }

    /**
     * Parse stream-keyword.
     *
     * @return Parsed stream-keyword node
     */
    private STNode parseStreamKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.STREAM_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.STREAM_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse let expression.
     * <p>
     * <code>
     * let-expr := let let-var-decl [, let-var-decl]* in expression
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseLetExpression(boolean isRhsExpr) {
        STNode letKeyword = parseLetKeyword();
        STNode letVarDeclarations = parseLetVarDeclarations(ParserRuleContext.LET_EXPR_LET_VAR_DECL, isRhsExpr);
        STNode inKeyword = parseInKeyword();

        // If the variable declaration list is empty, clone the letKeyword token with the given diagnostic.
        letKeyword = cloneWithDiagnosticIfListEmpty(letVarDeclarations, letKeyword,
                DiagnosticErrorCode.ERROR_MISSING_LET_VARIABLE_DECLARATION);

        // allow-actions flag is always false, since there will not be any actions
        // within the let-expr, due to the precedence.
        STNode expression = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        return STNodeFactory.createLetExpressionNode(letKeyword, letVarDeclarations, inKeyword, expression);
    }

    /**
     * Parse let-keyword.
     *
     * @return Let-keyword node
     */
    private STNode parseLetKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.LET_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.LET_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse let variable declarations.
     * <p>
     * <code>let-var-decl-list := let-var-decl [, let-var-decl]*</code>
     *
     * @return Parsed node
     */
    private STNode parseLetVarDeclarations(ParserRuleContext context, boolean isRhsExpr) {
        startContext(context);
        List<STNode> varDecls = new ArrayList<>();
        STToken nextToken = peek();

        // Make sure at least one let variable declaration is present
        if (isEndOfLetVarDeclarations(nextToken.kind)) {
            endContext();
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first variable declaration, that has no leading comma
        STNode varDec = parseLetVarDecl(isRhsExpr);
        varDecls.add(varDec);

        // Parse the remaining variable declarations
        nextToken = peek();
        STNode leadingComma;
        while (!isEndOfLetVarDeclarations(nextToken.kind)) {
            leadingComma = parseComma();
            varDecls.add(leadingComma);
            varDec = parseLetVarDecl(isRhsExpr);
            varDecls.add(varDec);
            nextToken = peek();
        }

        endContext();
        return STNodeFactory.createNodeList(varDecls);
    }

    private boolean isEndOfLetVarDeclarations(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case COMMA_TOKEN:
            case AT_TOKEN:
                return false;
            case IN_KEYWORD:
                return true;
            default:
                return !isTypeStartingToken(tokenKind);
        }
    }

    /**
     * Parse let variable declaration.
     * <p>
     * <code>let-var-decl := [annots] typed-binding-pattern = expression</code>
     *
     * @return Parsed node
     */
    private STNode parseLetVarDecl(boolean isRhsExpr) {
        STNode annot = parseOptionalAnnotations();
        STNode typedBindingPattern = parseTypedBindingPattern(ParserRuleContext.LET_EXPR_LET_VAR_DECL);
        STNode assign = parseAssignOp();

        // allow-actions flag is always false, since there will not be any actions
        // within the let-var-decl, due to the precedence.
        STNode expression = parseExpression(OperatorPrecedence.ANON_FUNC_OR_LET, isRhsExpr, false);
        return STNodeFactory.createLetVariableDeclarationNode(annot, typedBindingPattern, assign, expression);
    }

    /**
     * Parse raw backtick string template expression.
     * <p>
     * <code>BacktickString := `expression`</code>
     *
     * @return Template expression node
     */
    private STNode parseTemplateExpression() {
        STNode type = STNodeFactory.createEmptyNode();
        STNode startingBackTick = parseBacktickToken(ParserRuleContext.TEMPLATE_START);
        STNode content = parseTemplateContent();
        STNode endingBackTick = parseBacktickToken(ParserRuleContext.TEMPLATE_START);
        return STNodeFactory.createTemplateExpressionNode(SyntaxKind.RAW_TEMPLATE_EXPRESSION, type, startingBackTick,
                content, endingBackTick);
    }

    private STNode parseTemplateContent() {
        List<STNode> items = new ArrayList<>();
        STToken nextToken = peek();
        while (!isEndOfBacktickContent(nextToken.kind)) {
            STNode contentItem = parseTemplateItem();
            items.add(contentItem);
            nextToken = peek();
        }
        return STNodeFactory.createNodeList(items);
    }

    private boolean isEndOfBacktickContent(SyntaxKind kind) {
        switch (kind) {
            case EOF_TOKEN:
            case BACKTICK_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode parseTemplateItem() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.INTERPOLATION_START_TOKEN) {
            return parseInterpolation();
        }

        // Template string component
        return consume();
    }

    /**
     * Parse string template expression.
     * <p>
     * <code>string-template-expr := string ` expression `</code>
     *
     * @return String template expression node
     */
    private STNode parseStringTemplateExpression() {
        STNode type = parseStringKeyword();
        STNode startingBackTick = parseBacktickToken(ParserRuleContext.TEMPLATE_START);
        STNode content = parseTemplateContent();
        STNode endingBackTick = parseBacktickToken(ParserRuleContext.TEMPLATE_END);
        return STNodeFactory.createTemplateExpressionNode(SyntaxKind.STRING_TEMPLATE_EXPRESSION, type, startingBackTick,
                content, endingBackTick);
    }

    /**
     * Parse <code>string</code> keyword.
     *
     * @return string keyword node
     */
    private STNode parseStringKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.STRING_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.STRING_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse XML template expression.
     * <p>
     * <code>xml-template-expr := xml BacktickString</code>
     *
     * @return XML template expression
     */
    private STNode parseXMLTemplateExpression() {
        STNode xmlKeyword = parseXMLKeyword();
        STNode startingBackTick = parseBacktickToken(ParserRuleContext.TEMPLATE_START);
        STNode content = parseTemplateContentAsXML();
        STNode endingBackTick = parseBacktickToken(ParserRuleContext.TEMPLATE_END);
        return STNodeFactory.createTemplateExpressionNode(SyntaxKind.XML_TEMPLATE_EXPRESSION, xmlKeyword,
                startingBackTick, content, endingBackTick);
    }

    /**
     * Parse <code>xml</code> keyword.
     *
     * @return xml keyword node
     */
    private STNode parseXMLKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.XML_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.XML_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse the content of the template string as XML. This method first read the
     * input in the same way as the raw-backtick-template (BacktickString). Then
     * it parses the content as XML.
     *
     * @return XML node
     */
    private STNode parseTemplateContentAsXML() {
        // Separate out the interpolated expressions to a queue. Then merge the string content using '${}'.
        // These '&{}' are used to represent the interpolated locations. XML parser will replace '&{}' with
        // the actual interpolated expression, while building the XML tree.
        ArrayDeque<STNode> expressions = new ArrayDeque<>();
        StringBuilder xmlStringBuilder = new StringBuilder();
        STToken nextToken = peek();
        while (!isEndOfBacktickContent(nextToken.kind)) {
            STNode contentItem = parseTemplateItem();
            if (contentItem.kind == SyntaxKind.TEMPLATE_STRING) {
                xmlStringBuilder.append(((STToken) contentItem).text());
            } else {
                xmlStringBuilder.append("${}");
                expressions.add(contentItem);
            }
            nextToken = peek();
        }

        TextDocument textDocument = TextDocuments.from(xmlStringBuilder.toString());
        AbstractTokenReader tokenReader = new TokenReader(new XMLLexer(textDocument.getCharacterReader()));
        XMLParser xmlParser = new XMLParser(tokenReader, expressions);
        return xmlParser.parse();
    }

    /**
     * Parse interpolation of a back-tick string.
     * <p>
     * <code>
     * interpolation := ${ expression }
     * </code>
     *
     * @return Interpolation node
     */
    private STNode parseInterpolation() {
        startContext(ParserRuleContext.INTERPOLATION);
        STNode interpolStart = parseInterpolationStart();
        STNode expr = parseExpression();

        // Remove additional token in interpolation
        while (true) {
            STToken nextToken = peek();
            if (nextToken.kind == SyntaxKind.EOF_TOKEN || nextToken.kind == SyntaxKind.CLOSE_BRACE_TOKEN) {
                break;
            } else {
                nextToken = consume();
                expr = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(expr, nextToken,
                        DiagnosticErrorCode.ERROR_INVALID_TOKEN, nextToken.text());
            }
        }

        STNode closeBrace = parseCloseBrace();
        endContext();
        return STNodeFactory.createInterpolationNode(interpolStart, expr, closeBrace);
    }

    /**
     * Parse interpolation start token.
     * <p>
     * <code>interpolation-start := ${</code>
     *
     * @return Interpolation start token
     */
    private STNode parseInterpolationStart() {
        STToken token = peek();
        if (token.kind == SyntaxKind.INTERPOLATION_START_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.INTERPOLATION_START_TOKEN);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse back-tick token.
     *
     * @return Back-tick token
     */
    private STNode parseBacktickToken(ParserRuleContext ctx) {
        STToken token = peek();
        if (token.kind == SyntaxKind.BACKTICK_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ctx);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse table type descriptor.
     * <p>
     * table-type-descriptor := table row-type-parameter [key-constraint]
     * row-type-parameter := type-parameter
     * key-constraint := key-specifier | key-type-constraint
     * key-specifier := key ( [ field-name (, field-name)* ] )
     * key-type-constraint := key type-parameter
     * </p>
     *
     * @return Parsed table type desc node.
     */
    private STNode parseTableTypeDescriptor() {
        STNode tableKeywordToken = parseTableKeyword();
        STNode rowTypeParameterNode = parseRowTypeParameter();
        STNode keyConstraintNode;
        STToken nextToken = peek();
        if (isKeyKeyword(nextToken)) {
            STNode keyKeywordToken = getKeyKeyword(consume());
            keyConstraintNode = parseKeyConstraint(keyKeywordToken);
        } else {
            keyConstraintNode = STNodeFactory.createEmptyNode();
        }
        return STNodeFactory.createTableTypeDescriptorNode(tableKeywordToken, rowTypeParameterNode, keyConstraintNode);
    }

    /**
     * Parse row type parameter node.
     * <p>
     * row-type-parameter := type-parameter
     * </p>
     *
     * @return Parsed node.
     */
    private STNode parseRowTypeParameter() {
        startContext(ParserRuleContext.ROW_TYPE_PARAM);
        STNode rowTypeParameterNode = parseTypeParameter();
        endContext();
        return rowTypeParameterNode;
    }

    /**
     * Parse type parameter node.
     * <p>
     * type-parameter := < type-descriptor >
     * </p>
     *
     * @return Parsed node
     */
    private STNode parseTypeParameter() {
        STNode ltToken = parseLTToken();
        STNode typeNode = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_ANGLE_BRACKETS);
        STNode gtToken = parseGTToken();
        return STNodeFactory.createTypeParameterNode(ltToken, typeNode, gtToken);
    }

    /**
     * Parse key constraint.
     * <p>
     * key-constraint := key-specifier | key-type-constraint
     * </p>
     *
     * @return Parsed node.
     */
    private STNode parseKeyConstraint(STNode keyKeywordToken) {
        return parseKeyConstraint(peek().kind, keyKeywordToken);
    }

    private STNode parseKeyConstraint(SyntaxKind nextTokenKind, STNode keyKeywordToken) {
        switch (nextTokenKind) {
            case OPEN_PAREN_TOKEN:
                return parseKeySpecifier(keyKeywordToken);
            case LT_TOKEN:
                return parseKeyTypeConstraint(keyKeywordToken);
            default:
                Solution solution = recover(peek(), ParserRuleContext.KEY_CONSTRAINTS_RHS, keyKeywordToken);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }
                return parseKeyConstraint(solution.tokenKind, keyKeywordToken);
        }
    }

    /**
     * Parse key specifier given parsed key keyword token.
     * <p>
     * <code>key-specifier := key ( [ field-name (, field-name)* ] )</code>
     *
     * @return Parsed node
     */
    private STNode parseKeySpecifier(STNode keyKeywordToken) {
        startContext(ParserRuleContext.KEY_SPECIFIER);
        STNode openParenToken = parseOpenParenthesis(ParserRuleContext.OPEN_PARENTHESIS);
        STNode fieldNamesNode = parseFieldNames();
        STNode closeParenToken = parseCloseParenthesis();
        endContext();
        return STNodeFactory.createKeySpecifierNode(keyKeywordToken, openParenToken, fieldNamesNode, closeParenToken);
    }

    /**
     * Parse key type constraint.
     * <p>
     * key-type-constraint := key type-parameter
     * </p>
     *
     * @return Parsed node
     */
    private STNode parseKeyTypeConstraint(STNode keyKeywordToken) {
        STNode typeParameterNode = parseTypeParameter();
        return STNodeFactory.createKeyTypeConstraintNode(keyKeywordToken, typeParameterNode);
    }

    /**
     * Parse function type descriptor.
     * <p>
     * <code>function-type-descriptor := function function-signature</code>
     *
     * @return Function type descriptor node
     */
    private STNode parseFunctionTypeDesc() {
        startContext(ParserRuleContext.FUNC_TYPE_DESC);
        STNode functionKeyword = parseFunctionKeyword();
        STNode signature = parseFuncSignature(true);
        endContext();
        return STNodeFactory.createFunctionTypeDescriptorNode(functionKeyword, signature);
    }

    /**
     * Parse explicit anonymous function expression.
     * <p>
     * <code>explicit-anonymous-function-expr := [annots] function function-signature anon-func-body</code>
     *
     * @param annots Annotations.
     * @param isRhsExpr Is expression in rhs context
     * @return Anonymous function expression node
     */
    private STNode parseExplicitFunctionExpression(STNode annots, boolean isRhsExpr) {
        startContext(ParserRuleContext.ANON_FUNC_EXPRESSION);
        STNode funcKeyword = parseFunctionKeyword();
        STNode funcSignature = parseFuncSignature(false);
        STNode funcBody = parseAnonFuncBody(isRhsExpr);
        return STNodeFactory.createExplicitAnonymousFunctionExpressionNode(annots, funcKeyword, funcSignature,
                funcBody);
    }

    /**
     * Parse anonymous function body.
     * <p>
     * <code>anon-func-body := block-function-body | expr-function-body</code>
     *
     * @param isRhsExpr Is expression in rhs context
     * @return Anon function body node
     */
    private STNode parseAnonFuncBody(boolean isRhsExpr) {
        return parseAnonFuncBody(peek().kind, isRhsExpr);
    }

    private STNode parseAnonFuncBody(SyntaxKind nextTokenKind, boolean isRhsExpr) {
        switch (nextTokenKind) {
            case OPEN_BRACE_TOKEN:
            case EOF_TOKEN:
                STNode body = parseFunctionBodyBlock(true);
                endContext();
                return body;
            case RIGHT_DOUBLE_ARROW_TOKEN:
                // we end the anon-func context here, before going for expressions.
                // That is because we wouldn't know when will it end inside expressions.
                endContext();
                return parseExpressionFuncBody(true, isRhsExpr);
            default:
                Solution solution = recover(peek(), ParserRuleContext.ANON_FUNC_BODY, isRhsExpr);
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }
                return parseAnonFuncBody(solution.tokenKind, isRhsExpr);
        }
    }

    /**
     * Parse expression function body.
     * <p>
     * <code>expr-function-body := => expression</code>
     *
     * @param isAnon Is anonymous function.
     * @param isRhsExpr Is expression in rhs context
     * @return Expression function body node
     */
    private STNode parseExpressionFuncBody(boolean isAnon, boolean isRhsExpr) {
        STNode rightDoubleArrow = parseDoubleRightArrow();

        // Give high priority to the body-expr. This is done by lowering the current
        // precedence bewfore visiting the body.
        STNode expression = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);

        STNode semiColon;
        if (isAnon) {
            semiColon = STNodeFactory.createEmptyNode();
        } else {
            semiColon = parseSemicolon();
        }
        return STNodeFactory.createExpressionFunctionBodyNode(rightDoubleArrow, expression, semiColon);
    }

    /**
     * Parse '=>' token.
     *
     * @return Double right arrow token
     */
    private STNode parseDoubleRightArrow() {
        STToken token = peek();
        if (token.kind == SyntaxKind.RIGHT_DOUBLE_ARROW_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.EXPR_FUNC_BODY_START);
            return sol.recoveredNode;
        }
    }

    private STNode parseImplicitAnonFunc(STNode params, boolean isRhsExpr) {
        switch (params.kind) {
            case SIMPLE_NAME_REFERENCE:
            case INFER_PARAM_LIST:
                break;
            case BRACED_EXPRESSION:
                params = getAnonFuncParam((STBracedExpressionNode) params);
                break;
            default:
                STToken syntheticParam = STNodeFactory.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN);
                syntheticParam = SyntaxErrors.cloneWithLeadingInvalidNodeMinutiae(syntheticParam, params,
                        DiagnosticErrorCode.ERROR_INVALID_PARAM_LIST_IN_INFER_ANONYMOUS_FUNCTION_EXPR);
                params = STNodeFactory.createSimpleNameReferenceNode(syntheticParam);
        }

        STNode rightDoubleArrow = parseDoubleRightArrow();
        // start parsing the expr by giving higher-precedence to parse the right side arguments for right associative
        // operators. That is done by lowering the current precedence.
        STNode expression = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        return STNodeFactory.createImplicitAnonymousFunctionExpressionNode(params, rightDoubleArrow, expression);
    }

    /**
     * Create a new anon-func-param node from a braced expression.
     *
     * @param params Braced expression
     * @return Anon-func param node
     */
    private STNode getAnonFuncParam(STBracedExpressionNode params) {
        List<STNode> paramList = new ArrayList<>();
        paramList.add(params.expression);
        return STNodeFactory.createImplicitAnonymousFunctionParameters(params.openParen,
                STNodeFactory.createNodeList(paramList), params.closeParen);
    }

    /**
     * Parse implicit anon function expression.
     *
     * @param openParen Open parenthesis token
     * @param firstParam First parameter
     * @param isRhsExpr Is expression in rhs context
     * @return Implicit anon function expression node
     */
    private STNode parseImplicitAnonFunc(STNode openParen, STNode firstParam, boolean isRhsExpr) {
        List<STNode> paramList = new ArrayList<>();
        paramList.add(firstParam);

        // Parse the remaining params
        STToken nextToken = peek();
        STNode paramEnd;
        STNode param;
        while (!isEndOfAnonFuncParametersList(nextToken.kind)) {
            paramEnd = parseImplicitAnonFuncParamEnd(nextToken.kind);
            if (paramEnd == null) {
                break;
            }

            paramList.add(paramEnd);
            param = parseIdentifier(ParserRuleContext.IMPLICIT_ANON_FUNC_PARAM);
            param = STNodeFactory.createSimpleNameReferenceNode(param);
            paramList.add(param);
            nextToken = peek();
        }

        STNode params = STNodeFactory.createNodeList(paramList);
        STNode closeParen = parseCloseParenthesis();
        endContext(); // end arg list context

        STNode inferedParams = STNodeFactory.createImplicitAnonymousFunctionParameters(openParen, params, closeParen);
        return parseImplicitAnonFunc(inferedParams, isRhsExpr);
    }

    private STNode parseImplicitAnonFuncParamEnd() {
        return parseImplicitAnonFuncParamEnd(peek().kind);
    }

    private STNode parseImplicitAnonFuncParamEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_PAREN_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.ANON_FUNC_PARAM_RHS);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseImplicitAnonFuncParamEnd(solution.tokenKind);
        }
    }

    private boolean isEndOfAnonFuncParametersList(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
            case CLOSE_PAREN_TOKEN:
            case CLOSE_BRACKET_TOKEN:
            case SEMICOLON_TOKEN:
            case RETURNS_KEYWORD:
            case TYPE_KEYWORD:
            case LISTENER_KEYWORD:
            case IF_KEYWORD:
            case WHILE_KEYWORD:
            case OPEN_BRACE_TOKEN:
            case RIGHT_DOUBLE_ARROW_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse tuple type descriptor.
     * <p>
     * <code>tuple-type-descriptor := [ tuple-member-type-descriptors ]
     * <br/><br/>
     * tuple-member-type-descriptors := member-type-descriptor (, member-type-descriptor)* [, tuple-rest-descriptor]
     *                                     | [ tuple-rest-descriptor ]
     * <br/><br/>
     * tuple-rest-descriptor := type-descriptor ...
     * </code>
     *
     * @return
     */
    private STNode parseTupleTypeDesc() {
        STNode openBracket = parseOpenBracket();
        startContext(ParserRuleContext.TYPE_DESC_IN_TUPLE);
        STNode memberTypeDesc = parseTupleMemberTypeDescList();
        STNode closeBracket = parseCloseBracket();
        endContext();

        // If the tuple member type-desc list is empty, clone the openBracket token with the given diagnostic,
        openBracket = cloneWithDiagnosticIfListEmpty(memberTypeDesc, openBracket,
                DiagnosticErrorCode.ERROR_MISSING_TYPE_DESC);

        return STNodeFactory.createTupleTypeDescriptorNode(openBracket, memberTypeDesc, closeBracket);
    }

    /**
     * Parse tuple member type descriptors.
     *
     * @return Parsed node
     */
    private STNode parseTupleMemberTypeDescList() {
        List<STNode> typeDescList = new ArrayList<>();
        STToken nextToken = peek();

        // Return an empty list
        if (isEndOfTypeList(nextToken.kind)) {
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first typedesc, that has no leading comma
        STNode typeDesc = parseTypeDescriptorInternal(ParserRuleContext.TYPE_DESC_IN_TUPLE, false);

        return parseTupleTypeMembers(typeDesc, typeDescList);
    }

    private STNode parseTupleTypeMembers(STNode typeDesc, List<STNode> typeDescList) {
        STToken nextToken;
        // Parse the remaining type descs
        nextToken = peek();
        STNode tupleMemberRhs;
        while (!isEndOfTypeList(nextToken.kind)) {
            tupleMemberRhs = parseTupleMemberRhs(nextToken.kind);
            if (tupleMemberRhs == null) {
                break;
            }
            if (tupleMemberRhs.kind == SyntaxKind.ELLIPSIS_TOKEN) {
                typeDesc = STNodeFactory.createRestDescriptorNode(typeDesc, tupleMemberRhs);
                break;
            }
            typeDescList.add(typeDesc);
            typeDescList.add(tupleMemberRhs);
            typeDesc = parseTypeDescriptorInternal(ParserRuleContext.TYPE_DESC_IN_TUPLE, false);
            nextToken = peek();
        }

        typeDescList.add(typeDesc);

        return STNodeFactory.createNodeList(typeDescList);
    }

    private STNode parseTupleMemberRhs() {
        return parseTupleMemberRhs(peek().kind);
    }

    private STNode parseTupleMemberRhs(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACKET_TOKEN:
                return null;
            case ELLIPSIS_TOKEN:
                return parseEllipsis();
            default:
                Solution solution = recover(peek(), ParserRuleContext.TYPE_DESC_IN_TUPLE_RHS);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTupleMemberRhs(solution.tokenKind);
        }
    }

    private boolean isEndOfTypeList(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case CLOSE_BRACKET_TOKEN:
            case CLOSE_BRACE_TOKEN:
            case CLOSE_PAREN_TOKEN:
            case EOF_TOKEN:
            case EQUAL_TOKEN:
            case SEMICOLON_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse table constructor or query expression.
     * <p>
     * <code>
     * table-constructor-or-query-expr := table-constructor-expr | query-expr
     * <br/>
     * table-constructor-expr := table [key-specifier] [ [row-list] ]
     * <br/>
     * query-expr := [query-construct-type] query-pipeline select-clause
     *               [query-construct-type] query-pipeline select-clause on-conflict-clause? limit-lause?
     * <br/>
     * query-construct-type := table key-specifier | stream
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseTableConstructorOrQuery(boolean isRhsExpr) {
        startContext(ParserRuleContext.TABLE_CONSTRUCTOR_OR_QUERY_EXPRESSION);
        STNode tableOrQueryExpr = parseTableConstructorOrQuery(peek().kind, isRhsExpr);
        endContext();
        return tableOrQueryExpr;
    }

    private STNode parseTableConstructorOrQuery(SyntaxKind nextTokenKind, boolean isRhsExpr) {
        STNode queryConstructType;
        switch (nextTokenKind) {
            case FROM_KEYWORD:
                queryConstructType = STNodeFactory.createEmptyNode();
                return parseQueryExprRhs(queryConstructType, isRhsExpr);
            case STREAM_KEYWORD:
                queryConstructType = parseQueryConstructType(parseStreamKeyword(), null);
                return parseQueryExprRhs(queryConstructType, isRhsExpr);
            case TABLE_KEYWORD:
                STNode tableKeyword = parseTableKeyword();
                return parseTableConstructorOrQuery(tableKeyword, isRhsExpr);
            default:
                Solution solution = recover(peek(), ParserRuleContext.TABLE_CONSTRUCTOR_OR_QUERY_START, isRhsExpr);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTableConstructorOrQuery(solution.tokenKind, isRhsExpr);
        }

    }

    private STNode parseTableConstructorOrQuery(STNode tableKeyword, boolean isRhsExpr) {
        STToken nextToken = peek();
        return parseTableConstructorOrQuery(nextToken.kind, nextToken, tableKeyword, isRhsExpr);
    }

    private STNode parseTableConstructorOrQuery(SyntaxKind nextTokenKind, STToken nextToken, STNode tableKeyword,
                                                boolean isRhsExpr) {
        STNode keySpecifier;
        switch (nextTokenKind) {
            case OPEN_BRACKET_TOKEN:
                keySpecifier = STNodeFactory.createEmptyNode();
                return parseTableConstructorExprRhs(tableKeyword, keySpecifier);
            case KEY_KEYWORD:
                keySpecifier = parseKeySpecifier();
                return parseTableConstructorOrQueryRhs(tableKeyword, keySpecifier, isRhsExpr);
            case IDENTIFIER_TOKEN:
                if (isKeyKeyword(nextToken)) {
                    keySpecifier = parseKeySpecifier();
                    return parseTableConstructorOrQueryRhs(tableKeyword, keySpecifier, isRhsExpr);
                }
                // fall through
            default:
                Solution solution = recover(peek(), ParserRuleContext.TABLE_KEYWORD_RHS, tableKeyword, isRhsExpr);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTableConstructorOrQuery(solution.tokenKind, null, tableKeyword, isRhsExpr);
        }
    }

    private STNode parseTableConstructorOrQueryRhs(STNode tableKeyword, STNode keySpecifier, boolean isRhsExpr) {
        return parseTableConstructorOrQueryRhs(peek().kind, tableKeyword, keySpecifier, isRhsExpr);
    }

    private STNode parseTableConstructorOrQueryRhs(SyntaxKind nextTokenKind, STNode tableKeyword, STNode keySpecifier,
                                                   boolean isRhsExpr) {
        switch (nextTokenKind) {
            case FROM_KEYWORD:
                return parseQueryExprRhs(parseQueryConstructType(tableKeyword, keySpecifier), isRhsExpr);
            case OPEN_BRACKET_TOKEN:
                return parseTableConstructorExprRhs(tableKeyword, keySpecifier);
            default:
                Solution solution = recover(peek(), ParserRuleContext.TABLE_CONSTRUCTOR_OR_QUERY_RHS, tableKeyword,
                        keySpecifier, isRhsExpr);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTableConstructorOrQueryRhs(solution.tokenKind, tableKeyword, keySpecifier, isRhsExpr);
        }
    }

    /**
     * Parse query construct type.
     * <p>
     * <code>query-construct-type := table key-specifier | stream</code>
     *
     * @return Parsed node
     */
    private STNode parseQueryConstructType(STNode keyword, STNode keySpecifier) {
        return STNodeFactory.createQueryConstructTypeNode(keyword, keySpecifier);
    }

    /**
     * Parse query expression.
     * <p>
     * <code>
     * query-expr-rhs := query-pipeline select-clause
     *                   query-pipeline select-clause on-conflict-clause? limit-clause?
     * <br/>
     * query-pipeline := from-clause intermediate-clause*
     * </code>
     *
     * @param queryConstructType queryConstructType that precedes this rhs
     * @return Parsed node
     */
    private STNode parseQueryExprRhs(STNode queryConstructType, boolean isRhsExpr) {
        switchContext(ParserRuleContext.QUERY_EXPRESSION);
        STNode fromClause = parseFromClause(isRhsExpr);

        List<STNode> clauses = new ArrayList<>();
        STNode intermediateClause;
        STNode selectClause = null;
        while (!isEndOfIntermediateClause(peek().kind, SyntaxKind.NONE)) {
            intermediateClause = parseIntermediateClause(isRhsExpr);
            if (intermediateClause == null) {
                break;
            }

            // If there are more clauses after select clause they are add as invalid nodes to the select clause
            if (selectClause != null) {
                selectClause = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(selectClause, intermediateClause,
                        DiagnosticErrorCode.ERROR_MORE_CLAUSES_AFTER_SELECT_CLAUSE);
                continue;
            }

            if (intermediateClause.kind == SyntaxKind.SELECT_CLAUSE) {
                selectClause = intermediateClause;
            } else {
                clauses.add(intermediateClause);
            }
        }

        if (peek().kind == SyntaxKind.DO_KEYWORD) {
            STNode intermediateClauses = STNodeFactory.createNodeList(clauses);
            STNode queryPipeline = STNodeFactory.createQueryPipelineNode(fromClause, intermediateClauses);
            return parseQueryAction(queryPipeline, selectClause, isRhsExpr);
        }

        if (selectClause == null) {
            STNode selectKeyword = SyntaxErrors.createMissingToken(SyntaxKind.SELECT_KEYWORD);
            STNode expr = STNodeFactory
                    .createSimpleNameReferenceNode(SyntaxErrors.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN));
            selectClause = STNodeFactory.createSelectClauseNode(selectKeyword, expr);

            // Now we need to attach the diagnostic to the last intermediate clause.
            // If there are no intermediate clauses, then attach to the from clause.
            if (clauses.isEmpty()) {
                fromClause = SyntaxErrors.addDiagnostic(fromClause, DiagnosticErrorCode.ERROR_MISSING_SELECT_CLAUSE);
            } else {
                int lastIndex = clauses.size() - 1;
                STNode intClauseWithDiagnostic = SyntaxErrors.addDiagnostic(clauses.get(lastIndex),
                        DiagnosticErrorCode.ERROR_MISSING_SELECT_CLAUSE);
                clauses.set(lastIndex, intClauseWithDiagnostic);
            }
        }

        STNode intermediateClauses = STNodeFactory.createNodeList(clauses);
        STNode queryPipeline = STNodeFactory.createQueryPipelineNode(fromClause, intermediateClauses);
        STNode onConflictClause = parseOnConflictClause(isRhsExpr);
        STNode limitClause = parseLimitClause(isRhsExpr);
        return STNodeFactory.createQueryExpressionNode(queryConstructType, queryPipeline, selectClause,
                onConflictClause, limitClause);
    }

    /**
     * Parse limit keyword.
     *
     * @return Limit keyword node
     */
    private STNode parseLimitKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.LIMIT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.LIMIT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse an intermediate clause.
     * <p>
     * <code>
     * intermediate-clause := from-clause | where-clause | let-clause
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseIntermediateClause(boolean isRhsExpr) {
        STToken nextToken = peek();
        return parseIntermediateClause(nextToken.kind, isRhsExpr);
    }

    private STNode parseIntermediateClause(SyntaxKind nextTokenKind, boolean isRhsExpr) {
        switch (nextTokenKind) {
            case FROM_KEYWORD:
                return parseFromClause(isRhsExpr);
            case WHERE_KEYWORD:
                return parseWhereClause(isRhsExpr);
            case LET_KEYWORD:
                return parseLetClause(isRhsExpr);
            case SELECT_KEYWORD:
                return parseSelectClause(isRhsExpr);
            case JOIN_KEYWORD:
            case OUTER_KEYWORD:
                return parseJoinClause(isRhsExpr);
            case ORDER_KEYWORD:
            case BY_KEYWORD:
            case ASCENDING_KEYWORD:
            case DESCENDING_KEYWORD:
                return parseOrderByClause(isRhsExpr);

            case DO_KEYWORD:
            case SEMICOLON_TOKEN:
            case ON_KEYWORD:
            case CONFLICT_KEYWORD:
            case LIMIT_KEYWORD:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.QUERY_PIPELINE_RHS, isRhsExpr);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseIntermediateClause(solution.tokenKind, isRhsExpr);
        }
    }

    /**
     * Parse select-keyword.
     *
     * @return Select-keyword node
     */
    private STNode parseJoinKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.JOIN_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.JOIN_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse select-keyword.
     *
     * @return Select-keyword node
     */
    private STNode parseOuterKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.OUTER_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.OUTER_KEYWORD);
            return sol.recoveredNode;
        }
    }

    private boolean isEndOfIntermediateClause(SyntaxKind tokenKind, SyntaxKind precedingNodeKind) {
        switch (tokenKind) {
            case CLOSE_BRACE_TOKEN:
            case CLOSE_PAREN_TOKEN:
            case CLOSE_BRACKET_TOKEN:
            case OPEN_BRACE_TOKEN:
            case SEMICOLON_TOKEN:
            case PUBLIC_KEYWORD:
            case FUNCTION_KEYWORD:
            case EOF_TOKEN:
            case RESOURCE_KEYWORD:
            case LISTENER_KEYWORD:
            case DOCUMENTATION_STRING:
            case PRIVATE_KEYWORD:
            case RETURNS_KEYWORD:
            case SERVICE_KEYWORD:
            case TYPE_KEYWORD:
            case CONST_KEYWORD:
            case FINAL_KEYWORD:
            case DO_KEYWORD:
                return true;
            default:
                return isValidExprRhsStart(tokenKind, precedingNodeKind);
        }
    }

    /**
     * Parse from clause.
     * <p>
     * <code>from-clause := from typed-binding-pattern in expression</code>
     *
     * @return Parsed node
     */
    private STNode parseFromClause(boolean isRhsExpr) {
        STNode fromKeyword = parseFromKeyword();
        STNode typedBindingPattern = parseTypedBindingPattern(ParserRuleContext.FROM_CLAUSE);
        STNode inKeyword = parseInKeyword();

        // allow-actions flag is always false, since there will not be any actions
        // within the from-clause, due to the precedence.
        STNode expression = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        return STNodeFactory.createFromClauseNode(fromKeyword, typedBindingPattern, inKeyword, expression);
    }

    /**
     * Parse from-keyword.
     *
     * @return From-keyword node
     */
    private STNode parseFromKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.FROM_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FROM_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse where clause.
     * <p>
     * <code>where-clause := where expression</code>
     *
     * @return Parsed node
     */
    private STNode parseWhereClause(boolean isRhsExpr) {
        STNode whereKeyword = parseWhereKeyword();

        // allow-actions flag is always false, since there will not be any actions
        // within the where-clause, due to the precedence.
        STNode expression = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        return STNodeFactory.createWhereClauseNode(whereKeyword, expression);
    }

    /**
     * Parse where-keyword.
     *
     * @return Where-keyword node
     */
    private STNode parseWhereKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.WHERE_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.WHERE_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse let clause.
     * <p>
     * <code>let-clause := let let-var-decl [, let-var-decl]* </code>
     *
     * @return Parsed node
     */
    private STNode parseLetClause(boolean isRhsExpr) {
        STNode letKeyword = parseLetKeyword();
        STNode letVarDeclarations = parseLetVarDeclarations(ParserRuleContext.LET_CLAUSE_LET_VAR_DECL, isRhsExpr);

        // If the variable declaration list is empty, clone the letKeyword token with the given diagnostic.
        letKeyword = cloneWithDiagnosticIfListEmpty(letVarDeclarations, letKeyword,
                DiagnosticErrorCode.ERROR_MISSING_LET_VARIABLE_DECLARATION);

        return STNodeFactory.createLetClauseNode(letKeyword, letVarDeclarations);
    }

    /**
     * Parse order-keyword.
     *
     * @return Order-keyword node
     */
    private STNode parseOrderKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ORDER_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ORDER_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse by-keyword.
     *
     * @return By-keyword node
     */
    private STNode parseByKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.BY_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.BY_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse ascending-keyword.
     *
     * @return Ascending-keyword node
     */
    private STNode parseAscendingKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ASCENDING_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ASCENDING_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse descending-keyword.
     *
     * @return Descending-keyword node
     */
    private STNode parseDescendingKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.DESCENDING_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.DESCENDING_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse order by clause.
     * <p>
     * <code>order-by-clause := order by order-key-list
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseOrderByClause(boolean isRhsExpr) {
        STNode orderKeyword = parseOrderKeyword();
        STNode byKeyword = parseByKeyword();
        STNode orderKeys = parseOrderKeyList(isRhsExpr);
        byKeyword = cloneWithDiagnosticIfListEmpty(orderKeys, byKeyword, DiagnosticErrorCode.ERROR_MISSING_ORDER_KEY);

        return STNodeFactory.createOrderByClauseNode(orderKeyword, byKeyword, orderKeys);
    }

    /**
     * Parse order key.
     * <p>
     * <code>order-key-list := order-key [, order-key]*</code>
     *
     * @return Parsed node
     */
    private STNode parseOrderKeyList(boolean isRhsExpr) {
        startContext(ParserRuleContext.ORDER_KEY);
        List<STNode> orderKeys = new ArrayList<>();
        STToken nextToken = peek();

        if (isEndOfOrderKeys(nextToken.kind)) {
            endContext();
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first order key, that has no leading comma
        STNode orderKey = parseOrderKey(isRhsExpr);
        orderKeys.add(orderKey);

        // Parse the remaining order keys
        nextToken = peek();
        STNode orderKeyListMemberEnd;
        while (!isEndOfOrderKeys(nextToken.kind)) {
            orderKeyListMemberEnd = parseOrderKeyListMemberEnd();
            if (orderKeyListMemberEnd == null) {
                break;
            }
            orderKeys.add(orderKeyListMemberEnd);
            orderKey = parseOrderKey(isRhsExpr);
            orderKeys.add(orderKey);
            nextToken = peek();
        }

        endContext();
        return STNodeFactory.createNodeList(orderKeys);
    }

    private boolean isEndOfOrderKeys(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case COMMA_TOKEN:
                return false;
            case SEMICOLON_TOKEN:
            case EOF_TOKEN:
                return true;
            default:
                return isNextQueryClauseStart(tokenKind);
        }
    }

    private boolean isNextQueryClauseStart(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case SELECT_KEYWORD:
            case LET_KEYWORD:
            case WHERE_KEYWORD:
                return true;
            default:
                return false;
        }
    }

    private STNode parseOrderKeyListMemberEnd() {
        return parseOrderKeyListMemberEnd(peek().kind);
    }

    private STNode parseOrderKeyListMemberEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case SELECT_KEYWORD:
            case WHERE_KEYWORD:
            case LET_KEYWORD:
            case EOF_TOKEN:
                // null marks the end of order keys
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.ORDER_KEY_LIST_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseOrderKeyListMemberEnd(solution.tokenKind);
        }
    }

    /**
     * Parse order key.
     * <p>
     * <code>order-key := expression (ascending | descending)?</code>
     *
     * @return Parsed node
     */
    private STNode parseOrderKey(boolean isRhsExpr) {
        STNode expression = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        STToken nextToken = peek();
        switch (nextToken.kind) {
            case ASCENDING_KEYWORD:
                STNode ascendingKeyword = parseAscendingKeyword();
                return STNodeFactory.createOrderKeyNode(expression, ascendingKeyword);
            case DESCENDING_KEYWORD:
                STNode descendingKeyword = parseDescendingKeyword();
                return STNodeFactory.createOrderKeyNode(expression, descendingKeyword);
            default:
                return STNodeFactory.createOrderKeyNode(expression, STNodeFactory.createEmptyNode());
        }
    }

    /**
     * Parse select clause.
     * <p>
     * <code>select-clause := select expression</code>
     *
     * @return Parsed node
     */
    private STNode parseSelectClause(boolean isRhsExpr) {
        STNode selectKeyword = parseSelectKeyword();

        // allow-actions flag is always false, since there will not be any actions
        // within the select-clause, due to the precedence.
        STNode expression = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        return STNodeFactory.createSelectClauseNode(selectKeyword, expression);
    }

    /**
     * Parse select-keyword.
     *
     * @return Select-keyword node
     */
    private STNode parseSelectKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.SELECT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.SELECT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse on-conflict clause.
     * <p>
     * <code>
     * onConflictClause := on conflict expression
     * </code>
     *
     * @return On conflict clause node
     */
    private STNode parseOnConflictClause(boolean isRhsExpr) {
        // TODO: add error handling
        STToken nextToken = peek();
        if (nextToken.kind != SyntaxKind.ON_KEYWORD) {
            return STNodeFactory.createEmptyNode();
        }

        STNode onKeyword = parseOnKeyword();
        STNode conflictKeyword = parseConflictKeyword();
        STNode expr = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        return STNodeFactory.createOnConflictClauseNode(onKeyword, conflictKeyword, expr);
    }

    /**
     * Parse conflict keyword.
     *
     * @return Conflict keyword node
     */
    private STNode parseConflictKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.CONFLICT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.CONFLICT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse limit clause.
     * <p>
     * <code>limitClause := limit expression</code>
     *
     * @return Limit expression node
     */
    private STNode parseLimitClause(boolean isRhsExpr) {
        // TODO: add error handling
        STToken nextToken = peek();
        if (nextToken.kind != SyntaxKind.LIMIT_KEYWORD) {
            return STNodeFactory.createEmptyNode();
        }

        STNode limitKeyword = parseLimitKeyword();
        STNode expr = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        return STNodeFactory.createLimitClauseNode(limitKeyword, expr);
    }

    /**
     * Parse join clause.
     * <p>
     * <code>
     * join-clause := (join-var-decl | outer-join-var-decl) in expression
     * <br/>
     * join-var-decl := join (typeName | var) bindingPattern
     * <br/>
     * outer-join-var-decl := outer join var binding-pattern
     * </code>
     *
     * @return Join clause
     */
    private STNode parseJoinClause(boolean isRhsExpr) {
        // TODO Add error handling
        STNode outerKeyword;
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.OUTER_KEYWORD) {
            outerKeyword = parseOuterKeyword();
        } else {
            outerKeyword = STNodeFactory.createEmptyNode();
        }

        STNode joinKeyword = parseJoinKeyword();
        STNode typedBindingPattern = parseTypedBindingPattern(ParserRuleContext.JOIN_CLAUSE);
        STNode inKeyword = parseInKeyword();
        STNode onCondition;
        // allow-actions flag is always false, since there will not be any actions
        // within the from-clause, due to the precedence.
        STNode expression = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        nextToken = peek();
        if (nextToken.kind == SyntaxKind.ON_KEYWORD) {
            onCondition = parseOnClause(isRhsExpr);
        } else {
            onCondition = STNodeFactory.createEmptyNode();
        }
        return STNodeFactory.createJoinClauseNode(outerKeyword, joinKeyword, typedBindingPattern, inKeyword, expression,
                onCondition);
    }

    /**
     * Parse on clause.
     * <p>
     * <code>on clause := on expression</code>
     *
     * @return On clause node
     */
    private STNode parseOnClause(boolean isRhsExpr) {
        STNode onKeyword = parseOnKeyword();
        STNode expression = parseExpression(OperatorPrecedence.QUERY, isRhsExpr, false);
        return STNodeFactory.createOnClauseNode(onKeyword, expression);
    }

    /**
     * Parse start action.
     * <p>
     * <code>start-action := [annots] start (function-call-expr|method-call-expr|remote-method-call-action)</code>
     *
     * @return Start action node
     */
    private STNode parseStartAction(STNode annots) {
        STNode startKeyword = parseStartKeyword();
        STNode expr = parseActionOrExpression();

        // Validate expression or action in start action
        switch (expr.kind) {
            case FUNCTION_CALL:
            case METHOD_CALL:
            case REMOTE_METHOD_CALL_ACTION:
                break;
            case SIMPLE_NAME_REFERENCE:
            case QUALIFIED_NAME_REFERENCE:
                STNode openParenToken = SyntaxErrors.createMissingTokenWithDiagnostics(SyntaxKind.OPEN_PAREN_TOKEN,
                        DiagnosticErrorCode.ERROR_MISSING_OPEN_PAREN_TOKEN);
                STNode arguments = STNodeFactory.createEmptyNodeList();
                STNode closeParenToken = SyntaxErrors.createMissingTokenWithDiagnostics(SyntaxKind.CLOSE_PAREN_TOKEN,
                        DiagnosticErrorCode.ERROR_MISSING_CLOSE_PAREN_TOKEN);
                expr = STNodeFactory.createFunctionCallExpressionNode(expr, openParenToken, arguments, closeParenToken);
                break;
            default:
                startKeyword = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(startKeyword, expr,
                        DiagnosticErrorCode.ERROR_INVALID_EXPRESSION_IN_START_ACTION);
                STNode funcName = SyntaxErrors.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN);
                funcName = STNodeFactory.createSimpleNameReferenceNode(funcName);
                openParenToken = SyntaxErrors.createMissingToken(SyntaxKind.OPEN_PAREN_TOKEN);
                arguments = STNodeFactory.createEmptyNodeList();
                closeParenToken = SyntaxErrors.createMissingToken(SyntaxKind.CLOSE_PAREN_TOKEN);
                expr = STNodeFactory.createFunctionCallExpressionNode(funcName, openParenToken, arguments,
                        closeParenToken);
                break;
        }

        return STNodeFactory.createStartActionNode(getAnnotations(annots), startKeyword, expr);
    }

    /**
     * Parse start keyword.
     *
     * @return Start keyword node
     */
    private STNode parseStartKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.START_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.START_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse flush action.
     * <p>
     * <code>flush-action := flush [peer-worker]</code>
     *
     * @return flush action node
     */
    private STNode parseFlushAction() {
        STNode flushKeyword = parseFlushKeyword();
        STNode peerWorker = parseOptionalPeerWorkerName();
        return STNodeFactory.createFlushActionNode(flushKeyword, peerWorker);
    }

    /**
     * Parse flush keyword.
     *
     * @return flush keyword node
     */
    private STNode parseFlushKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.FLUSH_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.FLUSH_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse peer worker.
     * <p>
     * <code>peer-worker := worker-name | default</code>
     *
     * @return peer worker name node
     */
    private STNode parseOptionalPeerWorkerName() {
        STToken token = peek();
        switch (token.kind) {
            case IDENTIFIER_TOKEN:
            case DEFAULT_KEYWORD:
                return STNodeFactory.createSimpleNameReferenceNode(consume());
            default:
                return STNodeFactory.createEmptyNode();
        }
    }

    /**
     * Parse intersection type descriptor.
     * <p>
     * intersection-type-descriptor := type-descriptor & type-descriptor
     * </p>
     *
     * @return Parsed node
     */
    private STNode parseIntersectionTypeDescriptor(STNode leftTypeDesc, ParserRuleContext context,
                                                   boolean isTypedBindingPattern) {
        // we come here only after seeing & token hence consume.
        STNode bitwiseAndToken = consume();
        STNode rightTypeDesc = parseTypeDescriptor(context, isTypedBindingPattern, false);
        return createIntersectionTypeDesc(leftTypeDesc, bitwiseAndToken, rightTypeDesc);
    }

    private STNode createIntersectionTypeDesc(STNode leftTypeDesc, STNode bitwiseAndToken, STNode rightTypeDesc) {
        leftTypeDesc = validateForUsageOfVar(leftTypeDesc);
        rightTypeDesc = validateForUsageOfVar(rightTypeDesc);
        return STNodeFactory.createIntersectionTypeDescriptorNode(leftTypeDesc, bitwiseAndToken, rightTypeDesc);
    }

    /**
     * Parse singleton type descriptor.
     * <p>
     * singleton-type-descriptor := simple-const-expr
     * simple-const-expr :=
     * nil-literal
     * | boolean-literal
     * | [Sign] int-literal
     * | [Sign] floating-point-literal
     * | string-literal
     * | constant-reference-expr
     * </p>
     */
    private STNode parseSingletonTypeDesc() {
        STNode simpleContExpr = parseSimpleConstExpr();
        return STNodeFactory.createSingletonTypeDescriptorNode(simpleContExpr);
    }

    // TODO: Fix this properly
    private STNode parseSignedIntOrFloat() {
        STNode operator = parseUnaryOperator();
        STNode literal;
        STToken nextToken = peek();
        switch (nextToken.kind) {
            case HEX_INTEGER_LITERAL:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                literal = parseBasicLiteral();
                break;
            default: // decimal integer literal
                literal = parseDecimalIntLiteral(ParserRuleContext.DECIMAL_INTEGER_LITERAL);
                literal = STNodeFactory.createBasicLiteralNode(literal.kind, literal);
        }
        return STNodeFactory.createUnaryExpressionNode(operator, literal);
    }

    private boolean isSingletonTypeDescStart(SyntaxKind tokenKind, boolean inTypeDescCtx) {
        STToken nextNextToken = getNextNextToken(tokenKind);
        switch (tokenKind) {
            case STRING_LITERAL:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case NULL_KEYWORD:
                if (inTypeDescCtx || isValidTypeDescRHSOutSideTypeDescCtx(nextNextToken)) {
                    return true;
                }
                return false;
            case PLUS_TOKEN:
            case MINUS_TOKEN:
                return isIntOrFloat(nextNextToken);
            default:
                return false;
        }
    }

    static boolean isIntOrFloat(STToken token) {
        switch (token.kind) {
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                return true;
            default:
                return false;
        }
    }

    private boolean isValidTypeDescRHSOutSideTypeDescCtx(STToken token) {
        switch (token.kind) {
            case IDENTIFIER_TOKEN:
            case QUESTION_MARK_TOKEN:
            case OPEN_PAREN_TOKEN:
            case OPEN_BRACKET_TOKEN:
            case PIPE_TOKEN:
            case BITWISE_AND_TOKEN:
            case OPEN_BRACE_TOKEN:
            case ERROR_KEYWORD:
                return true;
            default:
                return false;
        }
    }

    /**
     * Check whether the parser reached to a valid expression start.
     *
     * @param nextTokenKind Kind of the next immediate token.
     * @param nextTokenIndex Index to the next token.
     * @return <code>true</code> if this is a start of a valid expression. <code>false</code> otherwise
     */
    private boolean isValidExpressionStart(SyntaxKind nextTokenKind, int nextTokenIndex) {
        nextTokenIndex++;
        switch (nextTokenKind) {
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                SyntaxKind nextNextTokenKind = peek(nextTokenIndex).kind;
                return nextNextTokenKind == SyntaxKind.SEMICOLON_TOKEN || nextNextTokenKind == SyntaxKind.COMMA_TOKEN ||
                        nextNextTokenKind == SyntaxKind.CLOSE_BRACKET_TOKEN ||
                        isValidExprRhsStart(nextNextTokenKind, SyntaxKind.SIMPLE_NAME_REFERENCE);
            case IDENTIFIER_TOKEN:
                return isValidExprRhsStart(peek(nextTokenIndex).kind, SyntaxKind.SIMPLE_NAME_REFERENCE);
            case OPEN_PAREN_TOKEN:
            case CHECK_KEYWORD:
            case CHECKPANIC_KEYWORD:
            case OPEN_BRACE_TOKEN:
            case TYPEOF_KEYWORD:
            case NEGATION_TOKEN:
            case EXCLAMATION_MARK_TOKEN:
            case TRAP_KEYWORD:
            case OPEN_BRACKET_TOKEN:
            case LT_TOKEN:
            case FROM_KEYWORD:
            case LET_KEYWORD:
            case BACKTICK_TOKEN:
            case NEW_KEYWORD:
            case FAIL_KEYWORD:
            case LEFT_ARROW_TOKEN:
                return true;
            case PLUS_TOKEN:
            case MINUS_TOKEN:
                return isValidExpressionStart(peek(nextTokenIndex).kind, nextTokenIndex);
            case FUNCTION_KEYWORD:

            case TABLE_KEYWORD:
                return peek(nextTokenIndex).kind == SyntaxKind.FROM_KEYWORD;
            case STREAM_KEYWORD:
                STToken nextNextToken = peek(nextTokenIndex);
                return nextNextToken.kind == SyntaxKind.KEY_KEYWORD ||
                        nextNextToken.kind == SyntaxKind.OPEN_BRACKET_TOKEN ||
                        nextNextToken.kind == SyntaxKind.FROM_KEYWORD;
            case ERROR_KEYWORD:
                return peek(nextTokenIndex).kind == SyntaxKind.OPEN_PAREN_TOKEN;
            case SERVICE_KEYWORD:
                return peek(nextTokenIndex).kind == SyntaxKind.OPEN_BRACE_TOKEN;
            case XML_KEYWORD:
            case STRING_KEYWORD:
                return peek(nextTokenIndex).kind == SyntaxKind.BACKTICK_TOKEN;

            // 'start' and 'flush' are start of actions, but not expressions.
            case START_KEYWORD:
            case FLUSH_KEYWORD:
            case WAIT_KEYWORD:
            default:
                return false;
        }
    }

    /**
     * Parse sync send action.
     * <p>
     * <code>sync-send-action := expression ->> peer-worker</code>
     *
     * @param expression LHS expression of the sync send action
     * @return Sync send action node
     */
    private STNode parseSyncSendAction(STNode expression) {
        STNode syncSendToken = parseSyncSendToken();
        STNode peerWorker = parsePeerWorkerName();
        return STNodeFactory.createSyncSendActionNode(expression, syncSendToken, peerWorker);
    }

    /**
     * Parse peer worker.
     * <p>
     * <code>peer-worker := worker-name | default</code>
     *
     * @return peer worker name node
     */
    private STNode parsePeerWorkerName() {
        STToken token = peek();
        switch (token.kind) {
            case IDENTIFIER_TOKEN:
            case DEFAULT_KEYWORD:
                return STNodeFactory.createSimpleNameReferenceNode(consume());
            default:
                Solution sol = recover(token, ParserRuleContext.PEER_WORKER_NAME);
                if (sol.action == Action.REMOVE) {
                    return sol.recoveredNode;
                }

                return STNodeFactory.createSimpleNameReferenceNode(sol.recoveredNode);
        }
    }

    /**
     * Parse sync send token.
     * <p>
     * <code>sync-send-token :=  ->> </code>
     *
     * @return sync send token
     */
    private STNode parseSyncSendToken() {
        STToken token = peek();
        if (token.kind == SyntaxKind.SYNC_SEND_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.SYNC_SEND_TOKEN);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse receive action.
     * <p>
     * <code>receive-action := single-receive-action | multiple-receive-action</code>
     *
     * @return Receive action
     */
    private STNode parseReceiveAction() {
        STNode leftArrow = parseLeftArrowToken();
        STNode receiveWorkers = parseReceiveWorkers();
        return STNodeFactory.createReceiveActionNode(leftArrow, receiveWorkers);
    }

    private STNode parseReceiveWorkers() {
        return parseReceiveWorkers(peek().kind);
    }

    private STNode parseReceiveWorkers(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case DEFAULT_KEYWORD:
            case IDENTIFIER_TOKEN:
                return parsePeerWorkerName();
            case OPEN_BRACE_TOKEN:
                return parseMultipleReceiveWorkers();
            default:
                Solution solution = recover(peek(), ParserRuleContext.RECEIVE_WORKERS);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseReceiveWorkers(solution.tokenKind);
        }
    }

    /**
     * Parse multiple worker receivers.
     * <p>
     * <code>{ receive-field (, receive-field)* }</code>
     *
     * @return Multiple worker receiver node
     */
    private STNode parseMultipleReceiveWorkers() {
        startContext(ParserRuleContext.MULTI_RECEIVE_WORKERS);
        STNode openBrace = parseOpenBrace();
        STNode receiveFields = parseReceiveFields();
        STNode closeBrace = parseCloseBrace();
        endContext();

        openBrace = cloneWithDiagnosticIfListEmpty(receiveFields, openBrace,
                DiagnosticErrorCode.ERROR_MISSING_RECEIVE_FIELD_IN_RECEIVE_ACTION);
        return STNodeFactory.createReceiveFieldsNode(openBrace, receiveFields, closeBrace);
    }

    private STNode parseReceiveFields() {
        List<STNode> receiveFields = new ArrayList<>();
        STToken nextToken = peek();

        // Return an empty list
        if (isEndOfReceiveFields(nextToken.kind)) {
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first receive field, that has no leading comma
        STNode receiveField = parseReceiveField();
        receiveFields.add(receiveField);

        // Parse the remaining receive fields
        nextToken = peek();
        STNode recieveFieldEnd;
        while (!isEndOfReceiveFields(nextToken.kind)) {
            recieveFieldEnd = parseReceiveFieldEnd(nextToken.kind);
            if (recieveFieldEnd == null) {
                break;
            }

            receiveFields.add(recieveFieldEnd);
            receiveField = parseReceiveField();
            receiveFields.add(receiveField);
            nextToken = peek();
        }

        return STNodeFactory.createNodeList(receiveFields);
    }

    private boolean isEndOfReceiveFields(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode parseReceiveFieldEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACE_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.RECEIVE_FIELD_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseReceiveFieldEnd(solution.tokenKind);
        }
    }

    private STNode parseReceiveField() {
        return parseReceiveField(peek().kind);
    }

    /**
     * Parse receive field.
     * <p>
     * <code>receive-field := peer-worker | field-name : peer-worker</code>
     *
     * @param nextTokenKind Kind of the next token
     * @return Receiver field node
     */
    private STNode parseReceiveField(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case DEFAULT_KEYWORD:
                STNode defaultKeyword = parseDefaultKeyword();
                return STNodeFactory.createSimpleNameReferenceNode(defaultKeyword);
            case IDENTIFIER_TOKEN:
                STNode identifier = parseIdentifier(ParserRuleContext.RECEIVE_FIELD_NAME);
                return createQualifiedReceiveField(identifier);
            default:
                Solution solution = recover(peek(), ParserRuleContext.RECEIVE_FIELD);

                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                if (solution.tokenKind == SyntaxKind.IDENTIFIER_TOKEN) {
                    return createQualifiedReceiveField(solution.recoveredNode);
                }

                return solution.recoveredNode;
        }
    }

    private STNode createQualifiedReceiveField(STNode identifier) {
        if (peek().kind != SyntaxKind.COLON_TOKEN) {
            return identifier;
        }

        STNode colon = parseColon();
        STNode peerWorker = parsePeerWorkerName();
        return STNodeFactory.createQualifiedNameReferenceNode(identifier, colon, peerWorker);
    }

    /**
     *
     * Parse left arrow (<-) token.
     *
     * @return left arrow token
     */
    private STNode parseLeftArrowToken() {
        STToken token = peek();
        if (token.kind == SyntaxKind.LEFT_ARROW_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.LEFT_ARROW_TOKEN);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse signed right shift token (>>).
     *
     * @return Parsed node
     */
    private STNode parseSignedRightShiftToken() {
        STNode openGTToken = consume();
        STToken endLGToken = consume();
        STNode doubleGTToken = STNodeFactory.createToken(SyntaxKind.DOUBLE_GT_TOKEN, openGTToken.leadingMinutiae(),
                endLGToken.trailingMinutiae());

        if (hasTrailingMinutiae(openGTToken)) {
            doubleGTToken = SyntaxErrors.addDiagnostic(doubleGTToken,
                    DiagnosticErrorCode.ERROR_NO_WHITESPACES_ALLOWED_IN_RIGHT_SHIFT_OP);
        }
        return doubleGTToken;
    }

    /**
     * Parse unsigned right shift token (>>>).
     *
     * @return Parsed node
     */
    private STNode parseUnsignedRightShiftToken() {
        STNode openGTToken = consume();
        STNode middleGTToken = consume();
        STNode endLGToken = consume();
        STNode unsignedRightShiftToken = STNodeFactory.createToken(SyntaxKind.TRIPPLE_GT_TOKEN,
                openGTToken.leadingMinutiae(), endLGToken.trailingMinutiae());

        boolean validOpenGTToken = !hasTrailingMinutiae(openGTToken);
        boolean validMiddleGTToken = !hasTrailingMinutiae(middleGTToken);
        if (validOpenGTToken && validMiddleGTToken) {
            return unsignedRightShiftToken;
        }

        unsignedRightShiftToken = SyntaxErrors.addDiagnostic(unsignedRightShiftToken,
                DiagnosticErrorCode.ERROR_NO_WHITESPACES_ALLOWED_IN_UNSIGNED_RIGHT_SHIFT_OP);
        return unsignedRightShiftToken;
    }

    /**
     * Parse wait action.
     * <p>
     * <code>wait-action := single-wait-action | multiple-wait-action | alternate-wait-action </code>
     *
     * @return Wait action node
     */
    private STNode parseWaitAction() {
        STNode waitKeyword = parseWaitKeyword();
        if (peek().kind == SyntaxKind.OPEN_BRACE_TOKEN) {
            return parseMultiWaitAction(waitKeyword);
        }

        return parseSingleOrAlternateWaitAction(waitKeyword);
    }

    /**
     * Parse wait keyword.
     *
     * @return wait keyword
     */
    private STNode parseWaitKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.WAIT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.WAIT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse single or alternate wait actions.
     * <p>
     * <code>
     * alternate-or-single-wait-action := wait wait-future-expr (| wait-future-expr)+
     * <br/>
     * wait-future-expr := expression but not mapping-constructor-expr
     * </code>
     *
     * @param waitKeyword wait keyword
     * @return Single or alternate wait action node
     */
    private STNode parseSingleOrAlternateWaitAction(STNode waitKeyword) {
        startContext(ParserRuleContext.ALTERNATE_WAIT_EXPRS);
        STToken nextToken = peek();

        // Return an empty list
        if (isEndOfWaitFutureExprList(nextToken.kind)) {
            endContext();
            STNode waitFutureExprs = STNodeFactory
                    .createSimpleNameReferenceNode(STNodeFactory.createMissingToken(SyntaxKind.IDENTIFIER_TOKEN));
            waitFutureExprs = SyntaxErrors.addDiagnostic(waitFutureExprs,
                    DiagnosticErrorCode.ERROR_MISSING_WAIT_FUTURE_EXPRESSION);
            return STNodeFactory.createWaitActionNode(waitKeyword, waitFutureExprs);
        }

        // Parse first wait, that has no leading comma
        List<STNode> waitFutureExprList = new ArrayList<>();
        STNode waitField = parseWaitFutureExpr();
        waitFutureExprList.add(waitField);

        // Parse remaining wait future expression
        nextToken = peek();
        STNode waitFutureExprEnd;
        while (!isEndOfWaitFutureExprList(nextToken.kind)) {
            waitFutureExprEnd = parseWaitFutureExprEnd(nextToken.kind, 1);
            if (waitFutureExprEnd == null) {
                break;
            }

            waitFutureExprList.add(waitFutureExprEnd);
            waitField = parseWaitFutureExpr();
            waitFutureExprList.add(waitField);
            nextToken = peek();
        }

        // https://github.com/ballerina-platform/ballerina-spec/issues/525
        // STNode waitFutureExprs = STNodeFactory.createNodeList(waitFutureExprList);
        endContext();
        return STNodeFactory.createWaitActionNode(waitKeyword, waitFutureExprList.get(0));
    }

    private boolean isEndOfWaitFutureExprList(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
            case SEMICOLON_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode parseWaitFutureExpr() {
        STNode waitFutureExpr = parseActionOrExpression();
        if (waitFutureExpr.kind == SyntaxKind.MAPPING_CONSTRUCTOR) {
            waitFutureExpr = SyntaxErrors.addDiagnostic(waitFutureExpr,
                    DiagnosticErrorCode.ERROR_MAPPING_CONSTRUCTOR_EXPR_AS_A_WAIT_EXPR);
        } else if (isAction(waitFutureExpr)) {
            waitFutureExpr =
                    SyntaxErrors.addDiagnostic(waitFutureExpr, DiagnosticErrorCode.ERROR_ACTION_AS_A_WAIT_EXPR);
        }
        return waitFutureExpr;
    }

    private STNode parseWaitFutureExprEnd(int nextTokenIndex) {
        return parseWaitFutureExprEnd(peek().kind, 1);
    }

    private STNode parseWaitFutureExprEnd(SyntaxKind nextTokenKind, int nextTokenIndex) {
        switch (nextTokenKind) {
            case PIPE_TOKEN:
                return parsePipeToken();
            default:
                if (isEndOfWaitFutureExprList(nextTokenKind) ||
                        !isValidExpressionStart(nextTokenKind, nextTokenIndex)) {
                    return null;
                }

                Solution solution = recover(peek(), ParserRuleContext.WAIT_FUTURE_EXPR_END, nextTokenIndex);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                // current token becomes next token
                return parseWaitFutureExprEnd(solution.tokenKind, 0);
        }
    }

    /**
     * Parse multiple wait action.
     * <p>
     * <code>multiple-wait-action := wait { wait-field (, wait-field)* }</code>
     *
     * @param waitKeyword Wait keyword
     * @return Multiple wait action node
     */
    private STNode parseMultiWaitAction(STNode waitKeyword) {
        startContext(ParserRuleContext.MULTI_WAIT_FIELDS);
        STNode openBrace = parseOpenBrace();
        STNode waitFields = parseWaitFields();
        STNode closeBrace = parseCloseBrace();
        endContext();

        openBrace = cloneWithDiagnosticIfListEmpty(waitFields, openBrace,
                DiagnosticErrorCode.ERROR_MISSING_WAIT_FIELD_IN_WAIT_ACTION);
        STNode waitFieldsNode = STNodeFactory.createWaitFieldsListNode(openBrace, waitFields, closeBrace);
        return STNodeFactory.createWaitActionNode(waitKeyword, waitFieldsNode);
    }

    private STNode parseWaitFields() {
        List<STNode> waitFields = new ArrayList<>();
        STToken nextToken = peek();

        // Return an empty list
        if (isEndOfWaitFields(nextToken.kind)) {
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first receive field, that has no leading comma
        STNode waitField = parseWaitField();
        waitFields.add(waitField);

        // Parse the remaining receive fields
        nextToken = peek();
        STNode waitFieldEnd;
        while (!isEndOfWaitFields(nextToken.kind)) {
            waitFieldEnd = parseWaitFieldEnd(nextToken.kind);
            if (waitFieldEnd == null) {
                break;
            }

            waitFields.add(waitFieldEnd);
            waitField = parseWaitField();
            waitFields.add(waitField);
            nextToken = peek();
        }

        return STNodeFactory.createNodeList(waitFields);
    }

    private boolean isEndOfWaitFields(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode parseWaitFieldEnd() {
        return parseWaitFieldEnd(peek().kind);
    }

    private STNode parseWaitFieldEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACE_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.WAIT_FIELD_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseWaitFieldEnd(solution.tokenKind);
        }
    }

    private STNode parseWaitField() {
        return parseWaitField(peek().kind);
    }

    /**
     * Parse wait field.
     * <p>
     * <code>wait-field := variable-name | field-name : wait-future-expr</code>
     *
     * @param nextTokenKind Kind of the next token
     * @return Receiver field node
     */
    private STNode parseWaitField(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN:
                STNode identifier = parseIdentifier(ParserRuleContext.WAIT_FIELD_NAME);
                identifier = STNodeFactory.createSimpleNameReferenceNode(identifier);
                return createQualifiedWaitField(identifier);
            default:
                Solution solution = recover(peek(), ParserRuleContext.WAIT_FIELD_NAME);

                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseWaitField(solution.tokenKind);
        }
    }

    private STNode createQualifiedWaitField(STNode identifier) {
        if (peek().kind != SyntaxKind.COLON_TOKEN) {
            return identifier;
        }

        STNode colon = parseColon();
        STNode waitFutureExpr = parseWaitFutureExpr();
        return STNodeFactory.createWaitFieldNode(identifier, colon, waitFutureExpr);
    }

    /**
     * Parse annot access expression.
     * <p>
     * <code>
     * annot-access-expr := expression .@ annot-tag-reference
     * <br/>
     * annot-tag-reference := qualified-identifier | identifier
     * </code>
     *
     * @param lhsExpr Preceding expression of the annot access access
     * @return Parsed node
     */
    private STNode parseAnnotAccessExpression(STNode lhsExpr, boolean isInConditionalExpr) {
        STNode annotAccessToken = parseAnnotChainingToken();
        STNode annotTagReference = parseFieldAccessIdentifier(isInConditionalExpr);
        return STNodeFactory.createAnnotAccessExpressionNode(lhsExpr, annotAccessToken, annotTagReference);
    }

    /**
     * Parse annot-chaining-token.
     *
     * @return Parsed node
     */
    private STNode parseAnnotChainingToken() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ANNOT_CHAINING_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ANNOT_CHAINING_TOKEN);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse field access identifier.
     * <p>
     * <code>field-access-identifier := qualified-identifier | identifier</code>
     *
     * @return Parsed node
     */
    private STNode parseFieldAccessIdentifier(boolean isInConditionalExpr) {
        return parseQualifiedIdentifier(ParserRuleContext.FIELD_ACCESS_IDENTIFIER, isInConditionalExpr);
    }

    /**
     * Parse query action.
     * <p>
     * <code>query-action := query-pipeline do-clause
     * <br/>
     * do-clause := do block-stmt
     * </code>
     *
     * @param queryPipeline Query pipeline
     * @param selectClause Select clause if any This is only for validation.
     * @return Query action node
     */
    private STNode parseQueryAction(STNode queryPipeline, STNode selectClause, boolean isRhsExpr) {
        if (selectClause != null) {
            queryPipeline = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(queryPipeline, selectClause,
                    DiagnosticErrorCode.ERROR_SELECT_CLAUSE_IN_QUERY_ACTION);
        }

        startContext(ParserRuleContext.DO_CLAUSE);
        STNode doKeyword = parseDoKeyword();
        STNode blockStmt = parseBlockNode();
        endContext();

        STNode limitClause = parseLimitClause(isRhsExpr);
        return STNodeFactory.createQueryActionNode(queryPipeline, doKeyword, blockStmt, limitClause);
    }

    /**
     * Parse 'do' keyword.
     *
     * @return do keyword node
     */
    private STNode parseDoKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.DO_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.DO_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse optional field access or xml optional attribute access expression.
     * <p>
     * <code>
     * optional-field-access-expr := expression ?. field-name
     * <br/>
     * xml-optional-attribute-access-expr := expression ?. xml-attribute-name
     * <br/>
     * xml-attribute-name := xml-qualified-name | qualified-identifier | identifier
     * <br/>
     * xml-qualified-name := xml-namespace-prefix : identifier
     * <br/>
     * xml-namespace-prefix := identifier
     * </code>
     *
     * @param lhsExpr Preceding expression of the optional access
     * @return Parsed node
     */
    private STNode parseOptionalFieldAccessExpression(STNode lhsExpr, boolean isInConditionalExpr) {
        STNode optionalFieldAccessToken = parseOptionalChainingToken();
        STNode fieldName = parseFieldAccessIdentifier(isInConditionalExpr);
        return STNodeFactory.createOptionalFieldAccessExpressionNode(lhsExpr, optionalFieldAccessToken, fieldName);
    }

    /**
     * Parse optional chaining token.
     *
     * @return parsed node
     */
    private STNode parseOptionalChainingToken() {
        STToken token = peek();
        if (token.kind == SyntaxKind.OPTIONAL_CHAINING_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.OPTIONAL_CHAINING_TOKEN);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse conditional expression.
     * <p>
     * <code>conditional-expr := expression ? expression : expression</code>
     *
     * @param lhsExpr Preceding expression of the question mark
     * @return Parsed node
     */
    private STNode parseConditionalExpression(STNode lhsExpr) {
        startContext(ParserRuleContext.CONDITIONAL_EXPRESSION);
        STNode questionMark = parseQuestionMark();
        // start parsing middle-expr, by giving higher-precedence to the middle-expr, over currently
        // parsing conditional expr. That is done by lowering the current precedence.
        STNode middleExpr = parseExpression(OperatorPrecedence.ANON_FUNC_OR_LET, true, false, true);

        // Special case "a ? b : c", since "b:c" matches to var-ref due to expr-precedence.
        STNode nextToken = peek();
        STNode endExpr;
        STNode colon;
        if (nextToken.kind != SyntaxKind.COLON_TOKEN && middleExpr.kind == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
            STQualifiedNameReferenceNode qualifiedNameRef = (STQualifiedNameReferenceNode) middleExpr;
            middleExpr = STNodeFactory.createSimpleNameReferenceNode(qualifiedNameRef.modulePrefix);
            colon = qualifiedNameRef.colon;
            endContext();
            endExpr = STNodeFactory.createSimpleNameReferenceNode(qualifiedNameRef.identifier);
        } else {
            colon = parseColon();
            endContext();
            // start parsing end-expr, by giving higher-precedence to the end-expr, over currently
            // parsing conditional expr. That is done by lowering the current precedence.
            endExpr = parseExpression(OperatorPrecedence.ANON_FUNC_OR_LET, true, false);
        }

        return STNodeFactory.createConditionalExpressionNode(lhsExpr, questionMark, middleExpr, colon, endExpr);
    }

    /**
     * Parse enum declaration.
     * <p>
     * module-enum-decl :=
     * metadata
     * [public] enum identifier { enum-member (, enum-member)* }
     * enum-member := metadata identifier [= const-expr]
     * </p>
     *
     * @param metadata
     * @param qualifier
     *
     * @return Parsed enum node.
     */
    private STNode parseEnumDeclaration(STNode metadata, STNode qualifier) {
        startContext(ParserRuleContext.MODULE_ENUM_DECLARATION);
        STNode enumKeywordToken = parseEnumKeyword();
        STNode identifier = parseIdentifier(ParserRuleContext.MODULE_ENUM_NAME);
        STNode openBraceToken = parseOpenBrace();
        STNode enumMemberList = parseEnumMemberList();
        STNode closeBraceToken = parseCloseBrace();

        endContext();
        openBraceToken = cloneWithDiagnosticIfListEmpty(enumMemberList, openBraceToken,
                DiagnosticErrorCode.ERROR_MISSING_ENUM_MEMBER);
        return STNodeFactory.createEnumDeclarationNode(metadata, qualifier, enumKeywordToken, identifier,
                openBraceToken, enumMemberList, closeBraceToken);
    }

    /**
     * Parse 'enum' keyword.
     *
     * @return enum keyword node
     */
    private STNode parseEnumKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ENUM_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ENUM_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse enum member list.
     * <p>
     * enum-member := metadata identifier [= const-expr]
     * </p>
     *
     * @return enum member list node.
     */
    private STNode parseEnumMemberList() {
        startContext(ParserRuleContext.ENUM_MEMBER_LIST);
        STToken nextToken = peek();

        // Report an empty enum member list
        if (nextToken.kind == SyntaxKind.CLOSE_BRACE_TOKEN) {
            return STNodeFactory.createEmptyNodeList();
        }

        // Parse first enum member, that has no leading comma
        List<STNode> enumMemberList = new ArrayList<>();
        STNode enumMember = parseEnumMember();

        // Parse the remaining enum members
        nextToken = peek();
        STNode enumMemberRhs;
        while (nextToken.kind != SyntaxKind.CLOSE_BRACE_TOKEN) {
            enumMemberRhs = parseEnumMemberEnd(nextToken.kind);
            if (enumMemberRhs == null) {
                break;
            }
            enumMemberList.add(enumMember);
            enumMemberList.add(enumMemberRhs);
            enumMember = parseEnumMember();
            nextToken = peek();
        }

        enumMemberList.add(enumMember);

        endContext();
        return STNodeFactory.createNodeList(enumMemberList);
    }

    /**
     * Parse enum member.
     * <p>
     * enum-member := metadata identifier [= const-expr]
     * </p>
     *
     * @return Parsed enum member node.
     */
    private STNode parseEnumMember() {
        STToken nextToken = peek();
        STNode metadata;
        switch (nextToken.kind) {
            case DOCUMENTATION_STRING:
            case AT_TOKEN:
                metadata = parseMetaData(nextToken.kind);
                break;
            default:
                metadata = STNodeFactory.createEmptyNode();
        }

        STNode identifierNode = parseIdentifier(ParserRuleContext.ENUM_MEMBER_NAME);
        return parseEnumMemberRhs(metadata, identifierNode);
    }

    private STNode parseEnumMemberRhs(STNode metadata, STNode identifierNode) {
        return parseEnumMemberRhs(peek().kind, metadata, identifierNode);
    }

    private STNode parseEnumMemberRhs(SyntaxKind nextToken, STNode metadata, STNode identifierNode) {
        STNode equalToken, constExprNode;
        switch (nextToken) {
            case EQUAL_TOKEN:
                equalToken = parseAssignOp();
                constExprNode = parseExpression();
                break;
            case COMMA_TOKEN:
            case CLOSE_BRACE_TOKEN:
                equalToken = STNodeFactory.createEmptyNode();
                constExprNode = STNodeFactory.createEmptyNode();
                break;
            default:
                Solution solution = recover(peek(), ParserRuleContext.ENUM_MEMBER_RHS, metadata, identifierNode);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseEnumMemberRhs(solution.tokenKind, metadata, identifierNode);
        }

        return STNodeFactory.createEnumMemberNode(metadata, identifierNode, equalToken, constExprNode);
    }

    private STNode parseEnumMemberEnd() {
        return parseEnumMemberEnd(peek().kind);
    }

    private STNode parseEnumMemberEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACE_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.ENUM_MEMBER_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseEnumMemberEnd(solution.tokenKind);
        }
    }

    /**
     * Parse transaction statement.
     * <p>
     * <code>transaction-stmt := "transaction" block-stmt ;</code>
     *
     * @return Transaction statement node
     */
    private STNode parseTransactionStatement() {
        startContext(ParserRuleContext.TRANSACTION_STMT);
        STNode transactionKeyword = parseTransactionKeyword();
        STNode blockStmt = parseBlockNode();
        endContext();
        return STNodeFactory.createTransactionStatementNode(transactionKeyword, blockStmt);
    }

    /**
     * Parse transaction keyword.
     *
     * @return parsed node
     */
    private STNode parseTransactionKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.TRANSACTION_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.TRANSACTION_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse commit action.
     * <p>
     * <code>commit-action := "commit"</code>
     *
     * @return Commit action node
     */
    private STNode parseCommitAction() {
        STNode commitKeyword = parseCommitKeyword();
        return STNodeFactory.createCommitActionNode(commitKeyword);
    }

    /**
     * Parse commit keyword.
     *
     * @return parsed node
     */
    private STNode parseCommitKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.COMMIT_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.COMMIT_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse retry statement.
     * <p>
     * <code>
     * retry-stmt := "retry" retry-spec block-stmt
     * <br/>
     * retry-spec :=  [type-parameter] [ "(" arg-list ")" ]
     * </code>
     *
     * @return Retry statement node
     */
    private STNode parseRetryStatement() {
        startContext(ParserRuleContext.RETRY_STMT);
        STNode retryKeyword = parseRetryKeyword();
        STNode retryStmt = parseRetryKeywordRhs(retryKeyword);
        endContext();
        return retryStmt;
    }

    private STNode parseRetryKeywordRhs(STNode retryKeyword) {
        return parseRetryKeywordRhs(peek().kind, retryKeyword);
    }

    private STNode parseRetryKeywordRhs(SyntaxKind nextTokenKind, STNode retryKeyword) {
        switch (nextTokenKind) {
            case LT_TOKEN:
                STNode typeParam = parseTypeParameter();
                return parseRetryTypeParamRhs(retryKeyword, typeParam);
            case OPEN_PAREN_TOKEN:
            case OPEN_BRACE_TOKEN:
            case TRANSACTION_KEYWORD:
                typeParam = STNodeFactory.createEmptyNode();
                return parseRetryTypeParamRhs(nextTokenKind, retryKeyword, typeParam);
            default:
                Solution solution = recover(peek(), ParserRuleContext.RETRY_KEYWORD_RHS, retryKeyword);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseRetryKeywordRhs(solution.tokenKind, retryKeyword);
        }
    }

    private STNode parseRetryTypeParamRhs(STNode retryKeyword, STNode typeParam) {
        return parseRetryTypeParamRhs(peek().kind, retryKeyword, typeParam);
    }

    private STNode parseRetryTypeParamRhs(SyntaxKind nextTokenKind, STNode retryKeyword, STNode typeParam) {
        STNode args;
        switch (nextTokenKind) {
            case OPEN_PAREN_TOKEN:
                args = parseParenthesizedArgList();
                break;
            case OPEN_BRACE_TOKEN:
            case TRANSACTION_KEYWORD:
                args = STNodeFactory.createEmptyNode();
                break;
            default:
                Solution solution = recover(peek(), ParserRuleContext.RETRY_TYPE_PARAM_RHS, retryKeyword, typeParam);
                return parseRetryTypeParamRhs(solution.tokenKind, retryKeyword, typeParam);
        }

        STNode blockStmt = parseRetryBody();
        return STNodeFactory.createRetryStatementNode(retryKeyword, typeParam, args, blockStmt);
    }

    private STNode parseRetryBody() {
        return parseRetryBody(peek().kind);
    }

    private STNode parseRetryBody(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case OPEN_BRACE_TOKEN:
                return parseBlockNode();
            case TRANSACTION_KEYWORD:
                return parseTransactionStatement();
            default:
                Solution solution = recover(peek(), ParserRuleContext.RETRY_BODY);
                return parseRetryBody(solution.tokenKind);
        }
    }

    /**
     * Parse retry keyword.
     *
     * @return parsed node
     */
    private STNode parseRetryKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.RETRY_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.RETRY_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse transaction statement.
     * <p>
     * <code>rollback-stmt := "rollback" [expression] ";"</code>
     *
     * @return Rollback statement node
     */
    private STNode parseRollbackStatement() {
        startContext(ParserRuleContext.ROLLBACK_STMT);
        STNode rollbackKeyword = parseRollbackKeyword();
        STNode expression;
        if (peek().kind == SyntaxKind.SEMICOLON_TOKEN) {
            expression = STNodeFactory.createEmptyNode();
        } else {
            expression = parseExpression();
        }

        STNode semicolon = parseSemicolon();
        endContext();
        return STNodeFactory.createRollbackStatementNode(rollbackKeyword, expression, semicolon);
    }

    /**
     * Parse rollback keyword.
     *
     * @return Rollback keyword node
     */
    private STNode parseRollbackKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.ROLLBACK_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ROLLBACK_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse transactional expression.
     * <p>
     * <code>transactional-expr := "transactional"</code>
     *
     * @return Transactional expression node
     */
    private STNode parseTransactionalExpression() {
        STNode transactionalKeyword = parseTransactionalKeyword();
        return STNodeFactory.createTransactionalExpressionNode(transactionalKeyword);
    }

    /**
     * Parse transactional keyword.
     *
     * @return Transactional keyword node
     */
    private STNode parseTransactionalKeyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.TRANSACTIONAL_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.ROLLBACK_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse service-constructor-expr.
     * <p>
     * <code>
     * service-constructor-expr := [annots] service service-body-block
     * <br/>
     * service-body-block := { service-method-defn* }
     * <br/>
     * service-method-defn := metadata [resource] function identifier function-signature method-defn-body
     * </code>
     *
     * @param annots Annotations
     * @return Service constructor expression node
     */
    private STNode parseServiceConstructorExpression(STNode annots) {
        startContext(ParserRuleContext.SERVICE_CONSTRUCTOR_EXPRESSION);
        STNode serviceKeyword = parseServiceKeyword();
        STNode serviceBody = parseServiceBody();
        endContext();
        return STNodeFactory.createServiceConstructorExpressionNode(annots, serviceKeyword, serviceBody);
    }

    /**
     * Parse base16 literal.
     * <p>
     * <code>
     * byte-array-literal := Base16Literal | Base64Literal
     * <br/>
     * Base16Literal := base16 WS ` HexGroup* WS `
     * <br/>
     * Base64Literal := base64 WS ` Base64Group* [PaddedBase64Group] WS `
     * </code>
     *
     * @param kind byte array literal kind
     * @return parsed node
     */
    private STNode parseByteArrayLiteral(SyntaxKind kind) {
        STNode type;
        if (kind == SyntaxKind.BASE16_KEYWORD) {
            type = parseBase16Keyword();
        } else {
            type = parseBase64Keyword();
        }

        STNode startingBackTick = parseBacktickToken(ParserRuleContext.TEMPLATE_START);
        if (startingBackTick.isMissing()) {
            startingBackTick = SyntaxErrors.createMissingToken(SyntaxKind.BACKTICK_TOKEN);
            STNode endingBackTick = SyntaxErrors.createMissingToken(SyntaxKind.BACKTICK_TOKEN);
            STNode content = STNodeFactory.createEmptyNode();
            STNode byteArrayLiteral =
                    STNodeFactory.createByteArrayLiteralNode(type, startingBackTick, content, endingBackTick);
            byteArrayLiteral =
                    SyntaxErrors.addDiagnostic(byteArrayLiteral, DiagnosticErrorCode.ERROR_MISSING_BYTE_ARRAY_CONTENT);
            return byteArrayLiteral;
        }

        STNode content = parseByteArrayContent(kind);
        return parseByteArrayLiteral(kind, type, startingBackTick, content);
    }

    /**
     * Parse byte array literal.
     *
     * @param baseKind indicates the SyntaxKind base16 or base64
     * @param typeKeyword keyword token, possible values are `base16` and `base64`
     * @param startingBackTick starting backtick token
     * @param byteArrayContent byte array literal content to be validated
     * @return parsed byte array literal node
     */
    private STNode parseByteArrayLiteral(SyntaxKind baseKind, STNode typeKeyword, STNode startingBackTick,
                                         STNode byteArrayContent) {
        STNode content = STNodeFactory.createEmptyNode();
        STNode newStartingBackTick = startingBackTick;

        STNodeList items = (STNodeList) byteArrayContent;
        if (items.size() == 1) {
            STNode item = items.get(0);
            if (baseKind == SyntaxKind.BASE16_KEYWORD && !isValidBase16LiteralContent(item.toString())) {
                newStartingBackTick = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(startingBackTick, item,
                        DiagnosticErrorCode.ERROR_INVALID_BASE16_CONTENT_IN_BYTE_ARRAY_LITERAL);
            } else if (baseKind == SyntaxKind.BASE64_KEYWORD && !isValidBase64LiteralContent(item.toString())) {
                newStartingBackTick = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(startingBackTick, item,
                        DiagnosticErrorCode.ERROR_INVALID_BASE64_CONTENT_IN_BYTE_ARRAY_LITERAL);
            } else if (item.kind != SyntaxKind.TEMPLATE_STRING) {
                newStartingBackTick = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(startingBackTick, item,
                        DiagnosticErrorCode.ERROR_INVALID_CONTENT_IN_BYTE_ARRAY_LITERAL);
            } else {
                content = item;
            }
        } else if (items.size() > 1) {
            // In this iteration, I am marking all the items as invalid
            STNode clonedStartingBackTick = startingBackTick;
            for (int index = 0; index < items.size(); index++) {
                STNode item = items.get(index);
                clonedStartingBackTick =
                        SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(clonedStartingBackTick, item);
            }
            newStartingBackTick = SyntaxErrors.addDiagnostic(clonedStartingBackTick,
                    DiagnosticErrorCode.ERROR_INVALID_CONTENT_IN_BYTE_ARRAY_LITERAL);
        }

        STNode endingBackTick = parseBacktickToken(ParserRuleContext.TEMPLATE_END);
        return STNodeFactory.createByteArrayLiteralNode(typeKeyword, newStartingBackTick, content, endingBackTick);
    }

    /**
     * Parse <code>base16</code> keyword.
     *
     * @return base16 keyword node
     */
    private STNode parseBase16Keyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.BASE16_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.BASE16_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse <code>base64</code> keyword.
     *
     * @return base64 keyword node
     */
    private STNode parseBase64Keyword() {
        STToken token = peek();
        if (token.kind == SyntaxKind.BASE64_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.BASE64_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Validate and parse byte array literal content.
     * An error is reported, if the content is invalid.
     *
     * @param kind byte array literal kind
     * @return parsed node
     */
    private STNode parseByteArrayContent(SyntaxKind kind) {
        STToken nextToken = peek();

        List<STNode> items = new ArrayList<>();
        while (!isEndOfBacktickContent(nextToken.kind)) {
            STNode content = parseTemplateItem();
            items.add(content);
            nextToken = peek();
        }

        return STNodeFactory.createNodeList(items);
    }

    /**
     * Validate base16 literal content.
     * <p>
     * <code>
     * Base16Literal := base16 WS ` HexGroup* WS `
     * <br/>
     * HexGroup := WS HexDigit WS HexDigit
     * <br/>
     * WS := WhiteSpaceChar*
     * <br/>
     * WhiteSpaceChar := 0x9 | 0xA | 0xD | 0x20
     * </code>
     *
     * @param content the string surrounded by the backticks
     * @return <code>true</code>, if the string content is valid. <code>false</code> otherwise.
     */
    static boolean isValidBase16LiteralContent(String content) {
        char[] charArray = content.toCharArray();
        int hexDigitCount = 0;

        for (char c : charArray) {
            switch (c) {
                case LexerTerminals.TAB:
                case LexerTerminals.NEWLINE:
                case LexerTerminals.CARRIAGE_RETURN:
                case LexerTerminals.SPACE:
                    break;
                default:
                    if (isHexDigit(c)) {
                        hexDigitCount++;
                    } else {
                        return false;
                    }
                    break;
            }
        }
        return hexDigitCount % 2 == 0;
    }

    /**
     * Validate base64 literal content.
     * <p>
     * <code>
     * Base64Literal := base64 WS ` Base64Group* [PaddedBase64Group] WS `
     * <br/>
     * Base64Group := WS Base64Char WS Base64Char WS Base64Char WS Base64Char
     * <br/>
     * PaddedBase64Group :=
     *    WS Base64Char WS Base64Char WS Base64Char WS PaddingChar
     *    | WS Base64Char WS Base64Char WS PaddingChar WS PaddingChar
     * <br/>
     * Base64Char := A .. Z | a .. z | 0 .. 9 | + | /
     * <br/>
     * PaddingChar := =
     * <br/>
     * WS := WhiteSpaceChar*
     * <br/>
     * WhiteSpaceChar := 0x9 | 0xA | 0xD | 0x20
     * </code>
     *
     * @param content the string surrounded by the backticks
     * @return <code>true</code>, if the string content is valid. <code>false</code> otherwise.
     */
    static boolean isValidBase64LiteralContent(String content) {
        char[] charArray = content.toCharArray();
        int base64CharCount = 0;
        int paddingCharCount = 0;

        for (char c : charArray) {
            switch (c) {
                case LexerTerminals.TAB:
                case LexerTerminals.NEWLINE:
                case LexerTerminals.CARRIAGE_RETURN:
                case LexerTerminals.SPACE:
                    break;
                case LexerTerminals.EQUAL:
                    paddingCharCount++;
                    break;
                default:
                    if (isBase64Char(c)) {
                        if (paddingCharCount == 0) {
                            base64CharCount++;
                        } else {
                            return false;
                        }
                    } else {
                        return false;
                    }
                    break;
            }
        }

        if (paddingCharCount > 2) {
            return false;
        } else if (paddingCharCount == 0) {
            return base64CharCount % 4 == 0;
        } else {
            return base64CharCount % 4 == 4 - paddingCharCount;
        }
    }

    /**
     * <p>
     * Check whether a given char is a base64 char.
     * </p>
     * <code>Base64Char := A .. Z | a .. z | 0 .. 9 | + | /</code>
     *
     * @param c character to check
     * @return <code>true</code>, if the character represents a base64 char. <code>false</code> otherwise.
     */
    static boolean isBase64Char(int c) {
        if ('a' <= c && c <= 'z') {
            return true;
        }
        if ('A' <= c && c <= 'Z') {
            return true;
        }
        if (c == '+' || c == '/') {
            return true;
        }
        return isDigit(c);
    }

    static boolean isHexDigit(int c) {
        if ('a' <= c && c <= 'f') {
            return true;
        }
        if ('A' <= c && c <= 'F') {
            return true;
        }
        return isDigit(c);
    }

    static boolean isDigit(int c) {
        return ('0' <= c && c <= '9');
    }

    /**
     * Parse xml filter expression.
     * <p>
     * <code>xml-filter-expr := expression .< xml-name-pattern ></code>
     *
     * @param lhsExpr Preceding expression of .< token
     * @return Parsed node
     */
    private STNode parseXMLFilterExpression(STNode lhsExpr) {
        STNode xmlNamePatternChain = parseXMLFilterExpressionRhs();
        return STNodeFactory.createXMLFilterExpressionNode(lhsExpr, xmlNamePatternChain);
    }

    /**
     * Parse xml filter expression rhs.
     * <p>
     * <code>filer-expression-rhs := .< xml-name-pattern ></code>
     *
     * @return Parsed node
     */
    private STNode parseXMLFilterExpressionRhs() {
        STNode dotLTToken = parseDotLTToken();
        return parseXMLNamePatternChain(dotLTToken);
    }

    /**
     * Parse xml name pattern chain.
     * <p>
     * <code>
     * xml-name-pattern-chain := filer-expression-rhs | xml-element-children-step | xml-element-descendants-step
     * <br/>
     * filer-expression-rhs := .< xml-name-pattern >
     * <br/>
     * xml-element-children-step := /< xml-name-pattern >
     * <br/>
     * xml-element-descendants-step := /**\/<xml-name-pattern >
     * </code>
     *
     * @param startToken Preceding token of xml name pattern
     * @return Parsed node
     */
    private STNode parseXMLNamePatternChain(STNode startToken) {
        startContext(ParserRuleContext.XML_NAME_PATTERN);
        STNode xmlNamePattern = parseXMLNamePattern();
        STNode gtToken = parseGTToken();
        endContext();

        startToken = cloneWithDiagnosticIfListEmpty(xmlNamePattern, startToken,
                DiagnosticErrorCode.ERROR_MISSING_XML_ATOMIC_NAME_PATTERN);
        return STNodeFactory.createXMLNamePatternChainingNode(startToken, xmlNamePattern, gtToken);
    }

    /**
     * Parse <code> .< </code> token.
     *
     * @return Parsed node
     */
    private STNode parseDotLTToken() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.DOT_LT_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(nextToken, ParserRuleContext.DOT_LT_TOKEN);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse xml name pattern.
     * <p>
     * <code>xml-name-pattern := xml-atomic-name-pattern [| xml-atomic-name-pattern]*</code>
     *
     * @return Parsed node
     */
    private STNode parseXMLNamePattern() {
        List<STNode> xmlAtomicNamePatternList = new ArrayList<>();
        STToken nextToken = peek();

        // Return an empty list
        if (isEndOfXMLNamePattern(nextToken.kind)) {
            return STNodeFactory.createNodeList(xmlAtomicNamePatternList);
        }

        // Parse first xml atomic name pattern, that has no leading pipe token
        STNode xmlAtomicNamePattern = parseXMLAtomicNamePattern();
        xmlAtomicNamePatternList.add(xmlAtomicNamePattern);

        // Parse the remaining xml atomic name patterns
        STNode separator;
        while (!isEndOfXMLNamePattern(peek().kind)) {
            separator = parseXMLNamePatternSeparator();
            if (separator == null) {
                break;
            }
            xmlAtomicNamePatternList.add(separator);

            xmlAtomicNamePattern = parseXMLAtomicNamePattern();
            xmlAtomicNamePatternList.add(xmlAtomicNamePattern);
        }

        return STNodeFactory.createNodeList(xmlAtomicNamePatternList);
    }

    private boolean isEndOfXMLNamePattern(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case GT_TOKEN:
            case EOF_TOKEN:
                return true;
            case IDENTIFIER_TOKEN:
            case ASTERISK_TOKEN:
            case COLON_TOKEN:
            default:
                return false;
        }
    }

    private STNode parseXMLNamePatternSeparator() {
        STToken token = peek();
        switch (token.kind) {
            case PIPE_TOKEN:
                return consume();
            case GT_TOKEN:
            case EOF_TOKEN:
                return null;
            default:
                Solution sol = recover(token, ParserRuleContext.XML_NAME_PATTERN_RHS);
                if (sol.tokenKind == SyntaxKind.GT_TOKEN) {
                    return null;
                }
                return sol.recoveredNode;
        }
    }

    /**
     * Parse xml atomic name pattern.
     * <p>
     * <code>
     * xml-atomic-name-pattern :=
     *   *
     *   | identifier
     *   | xml-namespace-prefix : identifier
     *   | xml-namespace-prefix : *
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseXMLAtomicNamePattern() {
        startContext(ParserRuleContext.XML_ATOMIC_NAME_PATTERN);
        STNode atomicNamePattern = parseXMLAtomicNamePatternBody();
        endContext();
        return atomicNamePattern;
    }

    private STNode parseXMLAtomicNamePatternBody() {
        STToken token = peek();
        STNode identifier;
        switch (token.kind) {
            case ASTERISK_TOKEN:
                return consume();
            case IDENTIFIER_TOKEN:
                identifier = consume();
                break;
            default:
                Solution sol = recover(token, ParserRuleContext.XML_ATOMIC_NAME_PATTERN_START);
                if (sol.action == Action.REMOVE) {
                    return sol.recoveredNode;
                }

                if (sol.recoveredNode.kind == SyntaxKind.ASTERISK_TOKEN) {
                    return sol.recoveredNode;
                }

                identifier = sol.recoveredNode;
                break;
        }

        return parseXMLAtomicNameIdentifier(identifier);
    }

    private STNode parseXMLAtomicNameIdentifier(STNode identifier) {
        STToken token = peek();
        if (token.kind == SyntaxKind.COLON_TOKEN) {
            STNode colon = consume();
            STToken nextToken = peek();
            if (nextToken.kind == SyntaxKind.IDENTIFIER_TOKEN || nextToken.kind == SyntaxKind.ASTERISK_TOKEN) {
                STToken endToken = consume();
                return STNodeFactory.createXMLAtomicNamePatternNode(identifier, colon, endToken);
            }
        }
        return STNodeFactory.createSimpleNameReferenceNode(identifier);
    }

    /**
     * Parse xml step expression.
     * <p>
     * <code>xml-step-expr := expression xml-step-start</code>
     *
     * @param lhsExpr Preceding expression of /*, /<, or /**\/< token
     * @return Parsed node
     */
    private STNode parseXMLStepExpression(STNode lhsExpr) {
        STNode xmlStepStart = parseXMLStepStart();
        return STNodeFactory.createXMLStepExpressionNode(lhsExpr, xmlStepStart);
    }

    /**
     * Parse xml filter expression rhs.
     * <p>
     * <code>
     *  xml-step-start :=
     *      xml-all-children-step
     *      | xml-element-children-step
     *      | xml-element-descendants-step
     * <br/>
     * xml-all-children-step := /*
     * </code>
     *
     * @return Parsed node
     */
    private STNode parseXMLStepStart() {
        STToken token = peek();
        STNode startToken;

        switch (token.kind) {
            case SLASH_ASTERISK_TOKEN:
                return consume();
            case DOUBLE_SLASH_DOUBLE_ASTERISK_LT_TOKEN:
                startToken = parseDoubleSlashDoubleAsteriskLTToken();
                break;
            case SLASH_LT_TOKEN:
            default:
                startToken = parseSlashLTToken();
                break;
        }
        return parseXMLNamePatternChain(startToken);
    }

    /**
     * Parse <code> /< </code> token.
     *
     * @return Parsed node
     */
    private STNode parseSlashLTToken() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.SLASH_LT_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(nextToken, ParserRuleContext.SLASH_LT_TOKEN);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse <code> /< </code> token.
     *
     * @return Parsed node
     */
    private STNode parseDoubleSlashDoubleAsteriskLTToken() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.DOUBLE_SLASH_DOUBLE_ASTERISK_LT_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(nextToken, ParserRuleContext.DOUBLE_SLASH_DOUBLE_ASTERISK_LT_TOKEN);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse match statement.
     * <p>
     * <code>match-stmt := match action-or-expr { match-clause+ }</code>
     *
     * @return Match statement
     */
    private STNode parseMatchStatement() {
        startContext(ParserRuleContext.MATCH_STMT);
        STNode matchKeyword = parseMatchKeyword();
        STNode actionOrExpr = parseActionOrExpression();
        startContext(ParserRuleContext.MATCH_BODY);
        STNode openBrace = parseOpenBrace();
        STNode matchClauses = parseMatchClauses();
        STNode closeBrace = parseCloseBrace();
        endContext();
        endContext();
        return STNodeFactory.createMatchStatementNode(matchKeyword, actionOrExpr, openBrace, matchClauses, closeBrace);
    }

    /**
     * Parse match keyword.
     *
     * @return Match keyword node
     */
    private STNode parseMatchKeyword() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.MATCH_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(nextToken, ParserRuleContext.MATCH_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse match clauses list.
     *
     * @return Match clauses list
     */
    private STNode parseMatchClauses() {
        List<STNode> matchClauses = new ArrayList<>();
        while (!isEndOfMatchClauses(peek().kind)) {
            STNode clause = parseMatchClause();
            matchClauses.add(clause);
        }
        return STNodeFactory.createNodeList(matchClauses);
    }

    private boolean isEndOfMatchClauses(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse a single match match clause.
     * <p>
     * <code>
     * match-clause := match-pattern-list [match-guard] => block-stmt
     * <br/>
     * match-guard := if expression
     * </code>
     *
     * @return A match clause
     */
    private STNode parseMatchClause() {
        STNode matchPatterns = parseMatchPatternList();
        STNode matchGuard = parseMatchGuard();
        STNode rightDoubleArrow = parseDoubleRightArrow();
        STNode blockStmt = parseBlockNode();
        return STNodeFactory.createMatchClauseNode(matchPatterns, matchGuard, rightDoubleArrow, blockStmt);
    }

    /**
     * Parse match guard.
     * <p>
     * <code>match-guard := if expression</code>
     *
     * @return Match guard
     */
    private STNode parseMatchGuard() {
        STToken nextToken = peek();
        return parseMatchGuard(nextToken.kind);
    }

    private STNode parseMatchGuard(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case IF_KEYWORD:
                STNode ifKeyword = parseIfKeyword();
                STNode expr = parseExpression(peek().kind, DEFAULT_OP_PRECEDENCE, true, false, true, false);
                return STNodeFactory.createMatchGuardNode(ifKeyword, expr);
            case RIGHT_DOUBLE_ARROW_TOKEN:
                return STNodeFactory.createEmptyNode();
            default:
                Solution solution = recover(peek(), ParserRuleContext.OPTIONAL_MATCH_GUARD);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseMatchGuard(solution.tokenKind);
        }
    }

    /**
     * Parse match patterns list.
     * <p>
     * <code>match-pattern-list := match-pattern (| match-pattern)*</code>
     *
     * @return Match patterns list
     */
    private STNode parseMatchPatternList() {
        startContext(ParserRuleContext.MATCH_PATTERN);
        List<STNode> matchClauses = new ArrayList<>();
        while (!isEndOfMatchPattern(peek().kind)) {
            STNode clause = parseMatchPattern();
            if (clause == null) {
                break;
            }
            matchClauses.add(clause);

            STNode seperator = parseMatchPatternEnd();
            if (seperator == null) {
                break;
            }
            matchClauses.add(seperator);
        }

        endContext();
        return STNodeFactory.createNodeList(matchClauses);
    }

    private boolean isEndOfMatchPattern(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case PIPE_TOKEN:
            case IF_KEYWORD:
            case RIGHT_ARROW_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse match pattern.
     * <p>
     * <code>
     * match-pattern := var binding-pattern
     *                  | wildcard-match-pattern
     *                  | const-pattern
     *                  | list-match-pattern
     *                  | mapping-match-pattern
     *                  | functional-match-pattern
     * </code>
     *
     * @return Match pattern
     */
    private STNode parseMatchPattern() {
        STToken nextToken = peek();
        return parseMatchPattern(nextToken.kind);
    }

    private STNode parseMatchPattern(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case OPEN_PAREN_TOKEN:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case PLUS_TOKEN:
            case MINUS_TOKEN:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
            case STRING_LITERAL:
                return parseSimpleConstExpr();
            case IDENTIFIER_TOKEN:
                // If it is an identifier it can be functional match pattern or const pattern
                STNode typeRefOrConstExpr = parseQualifiedIdentifier(ParserRuleContext.MATCH_PATTERN);
                return parseFunctionalMatchPatternOrConsPattern(typeRefOrConstExpr);
            case VAR_KEYWORD:
                return parseVarTypedBindingPattern();
            case OPEN_BRACKET_TOKEN:
                return parseListMatchPattern();
            case OPEN_BRACE_TOKEN:
                return parseMappingMatchPattern();
            case ERROR_KEYWORD:
                return parseFunctionalMatchPattern(consume());
            default:
                Solution solution = recover(peek(), ParserRuleContext.MATCH_PATTERN_START);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseMatchPattern(solution.tokenKind);
        }
    }

    private STNode parseMatchPatternEnd() {
        STToken nextToken = peek();
        return parseMatchPatternEnd(nextToken.kind);
    }

    private STNode parseMatchPatternEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case PIPE_TOKEN:
                return parsePipeToken();
            case IF_KEYWORD:
            case RIGHT_DOUBLE_ARROW_TOKEN:
                // Returning null indicates the end of the match-patterns list
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.MATCH_PATTERN_RHS);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseMatchPatternEnd(solution.tokenKind);
        }
    }

    /**
     * Parse var typed binding pattern.
     * <p>
     * <code>var binding-pattern</code>
     * </p>
     *
     * @return Parsed typed binding pattern node
     */
    private STNode parseVarTypedBindingPattern() {
        STNode varKeyword = parseVarKeyword();
        STNode bindingPattern = parseBindingPattern();
        return STNodeFactory.createTypedBindingPatternNode(varKeyword, bindingPattern);
    }

    /**
     * Parse var keyword.
     *
     * @return Var keyword node
     */
    private STNode parseVarKeyword() {
        STToken nextToken = peek();
        if (nextToken.kind == SyntaxKind.VAR_KEYWORD) {
            return consume();
        } else {
            Solution sol = recover(nextToken, ParserRuleContext.VAR_KEYWORD);
            return sol.recoveredNode;
        }
    }

    /**
     * Parse list match pattern.
     * <p>
     * <code>
     *         list-match-pattern := [ list-member-match-patterns ]
     *         list-member-match-patterns :=
     *          match-pattern (, match-pattern)* [, rest-match-pattern]
     *          | [ rest-match-pattern ]
     *     </code>
     * </p>
     *
     * @return Parsed list match pattern node
     */
    private STNode parseListMatchPattern() {
        startContext(ParserRuleContext.LIST_MATCH_PATTERN);
        STNode openBracketToken = parseOpenBracket();
        List<STNode> matchPatternList = new ArrayList<>();
        STNode restMatchPattern = null;
        STNode listMatchPatternMemberRhs = null;
        boolean isEndOfFields = false;

        while (!isEndOfListMatchPattern()) {
            STNode listMatchPatternMember = parseListMatchPatternMember();
            if (listMatchPatternMember.kind == SyntaxKind.REST_MATCH_PATTERN) {
                restMatchPattern = listMatchPatternMember;
                listMatchPatternMemberRhs = parseListMatchPatternMemberRhs();
                isEndOfFields = true;
                break;
            }
            matchPatternList.add(listMatchPatternMember);
            listMatchPatternMemberRhs = parseListMatchPatternMemberRhs();

            if (listMatchPatternMemberRhs != null) {
                matchPatternList.add(listMatchPatternMemberRhs);
            } else {
                break;
            }
        }

        // Following loop will only run if there are more fields after the rest match pattern.
        // Try to parse them and mark as invalid.
        while (isEndOfFields && listMatchPatternMemberRhs != null) {
            STNode invalidField = parseListMatchPatternMember();
            restMatchPattern =
                    SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(restMatchPattern, listMatchPatternMemberRhs);
            restMatchPattern = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(restMatchPattern, invalidField);
            restMatchPattern = SyntaxErrors.addDiagnostic(restMatchPattern,
                    DiagnosticErrorCode.ERROR_MORE_MATCH_PATTERNS_AFTER_REST_MATCH_PATTERN);
            listMatchPatternMemberRhs = parseListMatchPatternMemberRhs();
        }

        if (restMatchPattern == null) {
            restMatchPattern = STNodeFactory.createEmptyNode();
        }

        STNode matchPatternListNode = STNodeFactory.createNodeList(matchPatternList);
        STNode closeBracketToken = parseCloseBracket();
        endContext();

        return STNodeFactory.createListMatchPatternNode(openBracketToken, matchPatternListNode, restMatchPattern,
                closeBracketToken);
    }

    public boolean isEndOfListMatchPattern() {
        switch (peek().kind) {
            case CLOSE_BRACKET_TOKEN:
            case EOF_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode parseListMatchPatternMember() {
        STNode nextToken = peek();
        switch (nextToken.kind) {
            case ELLIPSIS_TOKEN:
                return parseRestMatchPattern();
            default:
                // No need of recovery here
                return parseMatchPattern();
        }
    }

    /**
     * Parse rest match pattern.
     * <p>
     * <code>
     *         rest-match-pattern := ... var variable-name
     *     </code>
     * </p>
     *
     * @return Parsed rest match pattern node
     */
    private STNode parseRestMatchPattern() {
        startContext(ParserRuleContext.REST_MATCH_PATTERN);
        STNode ellipsisToken = parseEllipsis();
        STNode varKeywordToken = parseVarKeyword();
        STNode variableName = parseVariableName();
        endContext();

        STSimpleNameReferenceNode simpleNameReferenceNode =
                (STSimpleNameReferenceNode) STNodeFactory.createSimpleNameReferenceNode(variableName);
        return STNodeFactory.createRestMatchPatternNode(ellipsisToken, varKeywordToken, simpleNameReferenceNode);
    }

    private STNode parseListMatchPatternMemberRhs() {
        return parseListMatchPatternMemberRhs(peek().kind);
    }

    private STNode parseListMatchPatternMemberRhs(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACKET_TOKEN:
            case EOF_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.LIST_MATCH_PATTERN_MEMBER_RHS);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseListMatchPatternMemberRhs(solution.tokenKind);
        }
    }

    /**
     * Parse mapping match pattern.
     * <p>
     * mapping-match-pattern := { field-match-patterns }
     * <br/>
     * field-match-patterns := field-match-pattern (, field-match-pattern)* [, rest-match-pattern]
     * | [ rest-match-pattern ]
     * <br/>
     * field-match-pattern := field-name : match-pattern
     * <br/>
     * rest-match-pattern := ... var variable-name
     * </p>
     *
     * @return Parsed Node.
     */
    private STNode parseMappingMatchPattern() {
        startContext(ParserRuleContext.MAPPING_MATCH_PATTERN);
        STNode openBraceToken = parseOpenBrace();
        List<STNode> fieldMatchPatternList = new ArrayList<>();
        STNode restMatchPattern = null;
        boolean isEndOfFields = false;

        while (!isEndOfMappingMatchPattern()) {
            STNode fieldMatchPatternMember = parseFieldMatchPatternMember();
            if (fieldMatchPatternMember.kind == SyntaxKind.REST_MATCH_PATTERN) {
                restMatchPattern = fieldMatchPatternMember;
                isEndOfFields = true;
                break;
            }
            fieldMatchPatternList.add(fieldMatchPatternMember);
            STNode fieldMatchPatternRhs = parseFieldMatchPatternRhs();

            if (fieldMatchPatternRhs != null) {
                fieldMatchPatternList.add(fieldMatchPatternRhs);
            } else {
                break;
            }
        }

        // Following loop will only run if there are more fields after the rest match pattern.
        // Try to parse them and mark as invalid.
        STNode fieldMatchPatternRhs = parseFieldMatchPatternRhs();
        while (isEndOfFields && fieldMatchPatternRhs != null) {
            STNode invalidField = parseFieldMatchPatternMember();
            restMatchPattern =
                    SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(restMatchPattern, fieldMatchPatternRhs);
            restMatchPattern = SyntaxErrors.cloneWithTrailingInvalidNodeMinutiae(restMatchPattern, invalidField);
            restMatchPattern = SyntaxErrors.addDiagnostic(restMatchPattern,
                    DiagnosticErrorCode.ERROR_MORE_FIELD_MATCH_PATTERNS_AFTER_REST_FIELD);
            fieldMatchPatternRhs = parseFieldMatchPatternRhs();
        }

        if (restMatchPattern == null) {
            restMatchPattern = STNodeFactory.createEmptyNode();
        }

        STNode fieldMatchPatterns = STNodeFactory.createNodeList(fieldMatchPatternList);
        STNode closeBraceToken = parseCloseBrace();
        endContext();

        return STNodeFactory.createMappingMatchPatternNode(openBraceToken, fieldMatchPatterns, restMatchPattern,
                closeBraceToken);
    }

    private STNode parseFieldMatchPatternMember() {
        return parseFieldMatchPatternMember(peek().kind);
    }

    private STNode parseFieldMatchPatternMember(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN:
                return parseFieldMatchPattern();
            case ELLIPSIS_TOKEN:
                return parseRestMatchPattern();
            default:
                Solution solution = recover(peek(), ParserRuleContext.FIELD_MATCH_PATTERN_MEMBER);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseFieldMatchPatternMember(solution.tokenKind);
        }
    }

    /**
     * Parse filed match pattern.
     * <p>
     * field-match-pattern := field-name : match-pattern
     * </p>
     *
     * @return Parsed field match pattern node
     */
    public STNode parseFieldMatchPattern() {
        STNode fieldNameNode = parseVariableName();
        STNode colonToken = parseColon();
        STNode matchPattern = parseMatchPattern();
        return STNodeFactory.createFieldMatchPatternNode(fieldNameNode, colonToken, matchPattern);
    }

    public boolean isEndOfMappingMatchPattern() {
        switch (peek().kind) {
            case CLOSE_BRACE_TOKEN:
            case EOF_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode parseFieldMatchPatternRhs() {
        return parseFieldMatchPatternRhs(peek().kind);
    }

    private STNode parseFieldMatchPatternRhs(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACE_TOKEN:
            case EOF_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.FIELD_MATCH_PATTERN_MEMBER_RHS);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseFieldMatchPatternRhs(solution.tokenKind);
        }
    }

    private STNode parseFunctionalMatchPatternOrConsPattern(STNode typeRefOrConstExpr) {
        return parseFunctionalMatchPatternOrConsPattern(peek().kind, typeRefOrConstExpr);
    }

    private STNode parseFunctionalMatchPatternOrConsPattern(SyntaxKind nextToken, STNode typeRefOrConstExpr) {
        switch (nextToken) {
            case OPEN_PAREN_TOKEN:
                return parseFunctionalMatchPattern(typeRefOrConstExpr);
            default:
                if (isMatchPatternEnd(peek().kind)) {
                    return typeRefOrConstExpr;
                }
                Solution solution =
                        recover(peek(), ParserRuleContext.FUNC_MATCH_PATTERN_OR_CONST_PATTERN, typeRefOrConstExpr);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseFunctionalMatchPatternOrConsPattern(solution.tokenKind, typeRefOrConstExpr);
        }
    }

    private boolean isMatchPatternEnd(SyntaxKind tokenKind) {
        switch (tokenKind) {
            case RIGHT_DOUBLE_ARROW_TOKEN:
            case COMMA_TOKEN:
            case CLOSE_BRACE_TOKEN:
            case CLOSE_BRACKET_TOKEN:
            case CLOSE_PAREN_TOKEN:
            case PIPE_TOKEN:
            case IF_KEYWORD:
            case EOF_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse functional match pattern.
     * <p>
     * functional-match-pattern := functionally-constructible-type-reference ( arg-list-match-pattern )
     * <br/>
     * functionally-constructible-type-reference := error | type-reference
     * <br/>
     * type-reference := identifier | qualified-identifier
     * <br/>
     * arg-list-match-pattern := positional-arg-match-patterns [, other-arg-match-patterns]
     * | other-arg-match-patterns
     * </p>
     *
     * @return Parsed functional match pattern node.
     */
    private STNode parseFunctionalMatchPattern(STNode typeRef) {
        startContext(ParserRuleContext.FUNCTIONAL_MATCH_PATTERN);
        STNode openParenthesisToken = parseOpenParenthesis(ParserRuleContext.OPEN_PARENTHESIS);
        STNode argListMatchPatternNode = parseArgListMatchPatterns();
        STNode closeParenthesisToken = parseCloseParenthesis();
        endContext();

        return STNodeFactory.createFunctionalMatchPatternNode(typeRef, openParenthesisToken, argListMatchPatternNode,
                closeParenthesisToken);
    }

    private STNode parseArgListMatchPatterns() {
        List<STNode> argListMatchPatterns = new ArrayList<>();
        SyntaxKind lastValidArgKind = SyntaxKind.IDENTIFIER_TOKEN;

        while (!isEndOfFunctionalMatchPattern()) {
            STNode currentArg = parseArgMatchPattern();
            DiagnosticErrorCode errorCode = validateArgMatchPatternOrder(lastValidArgKind, currentArg.kind);
            if (errorCode == null) {
                argListMatchPatterns.add(currentArg);
                lastValidArgKind = currentArg.kind;
            } else {
                updateLastNodeInListWithInvalidNode(argListMatchPatterns, currentArg, errorCode);
            }

            STNode argRhs = parseArgMatchPatternRhs();

            if (argRhs == null) {
                break;
            }

            if (errorCode == null) {
                argListMatchPatterns.add(argRhs);
            } else {
                updateLastNodeInListWithInvalidNode(argListMatchPatterns, argRhs, null);
            }
        }

        return STNodeFactory.createNodeList(argListMatchPatterns);
    }

    private boolean isEndOfFunctionalMatchPattern() {
        switch (peek().kind) {
            case CLOSE_PAREN_TOKEN:
            case EOF_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse arg match patterns.
     * <code>
     *     arg-match-pattern := match-pattern |   named-arg-match-pattern | rest-match-pattern
     * </code>
     * <br/>
     * <br/>
     *
     * @return parsed arg match pattern node.
     */
    private STNode parseArgMatchPattern() {
        return parseArgMatchPattern(peek().kind);
    }

    private STNode parseArgMatchPattern(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN:
                return parseNamedOrPositionalArgMatchPattern();
            case ELLIPSIS_TOKEN:
                return parseRestMatchPattern();
            case OPEN_PAREN_TOKEN:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case PLUS_TOKEN:
            case MINUS_TOKEN:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
            case STRING_LITERAL:
            case VAR_KEYWORD:
            case OPEN_BRACKET_TOKEN:
            case OPEN_BRACE_TOKEN:
            case ERROR_KEYWORD:
                return parseMatchPattern();
            default:
                Solution solution = recover(peek(), ParserRuleContext.ARG_MATCH_PATTERN);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseArgMatchPattern(solution.tokenKind);
        }
    }

    private STNode parseNamedOrPositionalArgMatchPattern() {
        STNode identifier = parseIdentifier(ParserRuleContext.MATCH_PATTERN_START);
        STToken secondToken = peek();
        switch (secondToken.kind) {
            case EQUAL_TOKEN:
                return parseNamedArgMatchPattern(identifier);
            case OPEN_PAREN_TOKEN:
                return parseFunctionalMatchPattern(identifier);
            case COMMA_TOKEN:
            case CLOSE_PAREN_TOKEN:
            default:
                return identifier;
        }
    }

    /**
     * Parses the next named arg match pattern.
     * <br/>
     * <code>named-arg-match-pattern := arg-name = match-pattern</code>
     * <br/>
     * <br/>
     *
     * @return arg match pattern list node added the new arg match pattern
     */
    private STNode parseNamedArgMatchPattern(STNode identifier) {
        startContext(ParserRuleContext.NAMED_ARG_MATCH_PATTERN);
        STNode equalToken = parseAssignOp();
        STNode matchPattern = parseMatchPattern();
        endContext();
        return STNodeFactory.createNamedArgMatchPatternNode(identifier, equalToken, matchPattern);
    }

    private STNode parseArgMatchPatternRhs() {
        return parseArgMatchPatternRhs(peek().kind);
    }

    private STNode parseArgMatchPatternRhs(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_PAREN_TOKEN:
            case EOF_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.ARG_MATCH_PATTERN_RHS);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseArgMatchPatternRhs(solution.tokenKind);
        }
    }

    private DiagnosticErrorCode validateArgMatchPatternOrder(SyntaxKind prevArgKind, SyntaxKind currentArgKind) {
        DiagnosticErrorCode errorCode = null;
        switch (prevArgKind) {
            case NAMED_ARG_MATCH_PATTERN:
                if (currentArgKind != SyntaxKind.NAMED_ARG_MATCH_PATTERN &&
                        currentArgKind != SyntaxKind.REST_MATCH_PATTERN) {
                    errorCode = DiagnosticErrorCode.ERROR_NAMED_ARG_FOLLOWED_BY_POSITIONAL_ARG;
                }
                break;
            case REST_MATCH_PATTERN:
                // Nothing is allowed after a rest arg
                errorCode = DiagnosticErrorCode.ERROR_ARG_FOLLOWED_BY_REST_ARG;
                break;
            default:
                break;
        }
        return errorCode;
    }

    /**
     * Parse markdown documentation.
     *
     * @return markdown documentation node
     */
    private STNode parseMarkdownDocumentation() {
        List<STNode> markdownDocLineList = new ArrayList<>();

        // With multi-line documentation, there could be more than one documentation string.
        // e.g.
        // # line1 (this is captured as one documentation string)
        //
        // # line2 (this is captured as another documentation string)
        STToken nextToken = peek();
        while (nextToken.kind == SyntaxKind.DOCUMENTATION_STRING) {
            STToken documentationString = consume();
            STNode parsedDocLines = parseDocumentationString(documentationString);
            appendParsedDocumentationLines(markdownDocLineList, parsedDocLines);
            nextToken = peek();
        }

        STNode markdownDocLines = STNodeFactory.createNodeList(markdownDocLineList);
        return STNodeFactory.createMarkdownDocumentationNode(markdownDocLines);
    }

    /**
     * Parse documentation string.
     *
     * @return markdown documentation line list node
     */
    private STNode parseDocumentationString(STToken documentationStringToken) {
        List<STNode> leadingTriviaList = getLeadingTriviaList(documentationStringToken.leadingMinutiae());
        TextDocument textDocument = TextDocuments.from(documentationStringToken.text());

        DocumentationLexer documentationLexer =
                new DocumentationLexer(textDocument.getCharacterReader(), leadingTriviaList);
        AbstractTokenReader tokenReader = new TokenReader(documentationLexer);
        DocumentationParser documentationParser = new DocumentationParser(tokenReader);

        return documentationParser.parse();
    }

    private List<STNode> getLeadingTriviaList(STNode leadingMinutiaeNode) {
        List<STNode> leadingTriviaList = new ArrayList<>();

        int bucketCount = leadingMinutiaeNode.bucketCount();
        for (int i = 0; i < bucketCount; i++) {
            leadingTriviaList.add(leadingMinutiaeNode.childInBucket(i));
        }
        return leadingTriviaList;
    }

    private void appendParsedDocumentationLines(List<STNode> markdownDocLineList, STNode parsedDocLines) {
        int bucketCount = parsedDocLines.bucketCount();
        for (int i = 0; i < bucketCount; i++) {
            STNode markdownDocLine = parsedDocLines.childInBucket(i);
            markdownDocLineList.add(markdownDocLine);
        }
    }

    // ------------------------ Ambiguity resolution at statement start ---------------------------

    /**
     * Parse any statement that starts with a token that has ambiguity between being
     * a type-desc or an expression.
     *
     * @param annots Annotations
     * @return Statement node
     */
    private STNode parseStmtStartsWithTypeOrExpr(SyntaxKind nextTokenKind, STNode annots) {
        startContext(ParserRuleContext.AMBIGUOUS_STMT);
        STNode typeOrExpr = parseTypedBindingPatternOrExpr(nextTokenKind, true);
        return parseStmtStartsWithTypedBPOrExprRhs(annots, typeOrExpr);
    }

    private STNode parseStmtStartsWithTypedBPOrExprRhs(STNode annots, STNode typedBindingPatternOrExpr) {
        if (typedBindingPatternOrExpr.kind == SyntaxKind.TYPED_BINDING_PATTERN) {
            STNode finalKeyword = STNodeFactory.createEmptyNode();
            switchContext(ParserRuleContext.VAR_DECL_STMT);
            return parseVarDeclRhs(annots, finalKeyword, typedBindingPatternOrExpr, false);
        }

        STNode expr = getExpression(typedBindingPatternOrExpr);
        expr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, expr, false, true);
        return parseStatementStartWithExprRhs(expr);
    }

    private STNode parseTypedBindingPatternOrExpr(boolean allowAssignment) {
        STToken nextToken = peek();
        return parseTypedBindingPatternOrExpr(nextToken.kind, allowAssignment);
    }

    private STNode parseTypedBindingPatternOrExpr(SyntaxKind nextTokenKind, boolean allowAssignment) {
        STNode typeOrExpr;
        switch (nextTokenKind) {
            case OPEN_PAREN_TOKEN:
                return parseTypedBPOrExprStartsWithOpenParenthesis();
            case FUNCTION_KEYWORD:
                return parseAnonFuncExprOrTypedBPWithFuncType();
            case IDENTIFIER_TOKEN:
                typeOrExpr = parseQualifiedIdentifier(ParserRuleContext.TYPE_NAME_OR_VAR_NAME);
                return parseTypedBindingPatternOrExprRhs(typeOrExpr, allowAssignment);
            case OPEN_BRACKET_TOKEN:
                typeOrExpr = parseTypedDescOrExprStartsWithOpenBracket();
                return parseTypedBindingPatternOrExprRhs(typeOrExpr, allowAssignment);

            // Can be a singleton type or expression.
            case NIL_LITERAL:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                STNode basicLiteral = parseBasicLiteral();
                return parseTypedBindingPatternOrExprRhs(basicLiteral, allowAssignment);
            default:
                if (isValidExpressionStart(nextTokenKind, 1)) {
                    return parseActionOrExpressionInLhs(nextTokenKind, null);
                }

                return parseTypedBindingPattern(ParserRuleContext.VAR_DECL_STMT);
        }
    }

    /**
     * Parse the component after the ambiguous starting node. Ambiguous node could be either an expr
     * or a type-desc. The component followed by this ambiguous node could be the binding-pattern or
     * the expression-rhs.
     *
     * @param typeOrExpr Type desc or the expression
     * @param allowAssignment Flag indicating whether to allow assignment. i.e.: whether this is a
     *            valid lvalue expression
     * @return Typed-binding-pattern node or an expression node
     */
    private STNode parseTypedBindingPatternOrExprRhs(STNode typeOrExpr, boolean allowAssignment) {
        STToken nextToken = peek();
        return parseTypedBindingPatternOrExprRhs(nextToken.kind, typeOrExpr, allowAssignment);
    }

    private STNode parseTypedBindingPatternOrExprRhs(SyntaxKind nextTokenKind, STNode typeOrExpr,
                                                     boolean allowAssignment) {
        switch (nextTokenKind) {
            case PIPE_TOKEN:
                STToken nextNextToken = peek(2);
                if (nextNextToken.kind == SyntaxKind.EQUAL_TOKEN) {
                    return typeOrExpr;
                }

                STNode pipe = parsePipeToken();
                STNode rhsTypedBPOrExpr = parseTypedBindingPatternOrExpr(allowAssignment);
                if (rhsTypedBPOrExpr.kind == SyntaxKind.TYPED_BINDING_PATTERN) {
                    STTypedBindingPatternNode typedBP = (STTypedBindingPatternNode) rhsTypedBPOrExpr;
                    typeOrExpr = getTypeDescFromExpr(typeOrExpr);
                    STNode newTypeDesc = createUnionTypeDesc(typeOrExpr, pipe, typedBP.typeDescriptor);
                    return STNodeFactory.createTypedBindingPatternNode(newTypeDesc, typedBP.bindingPattern);
                }

                return STNodeFactory.createBinaryExpressionNode(SyntaxKind.BINARY_EXPRESSION, typeOrExpr, pipe,
                        rhsTypedBPOrExpr);
            case BITWISE_AND_TOKEN:
                nextNextToken = peek(2);
                if (nextNextToken.kind == SyntaxKind.EQUAL_TOKEN) {
                    return typeOrExpr;
                }

                STNode ampersand = parseBinaryOperator();
                rhsTypedBPOrExpr = parseTypedBindingPatternOrExpr(allowAssignment);
                if (rhsTypedBPOrExpr.kind == SyntaxKind.TYPED_BINDING_PATTERN) {
                    STTypedBindingPatternNode typedBP = (STTypedBindingPatternNode) rhsTypedBPOrExpr;
                    typeOrExpr = getTypeDescFromExpr(typeOrExpr);
                    STNode newTypeDesc = createIntersectionTypeDesc(typeOrExpr, ampersand, typedBP.typeDescriptor);
                    return STNodeFactory.createTypedBindingPatternNode(newTypeDesc, typedBP.bindingPattern);
                }

                return STNodeFactory.createBinaryExpressionNode(SyntaxKind.BINARY_EXPRESSION, typeOrExpr, ampersand,
                        rhsTypedBPOrExpr);
            case SEMICOLON_TOKEN:
                if (isDefiniteExpr(typeOrExpr.kind)) {
                    return typeOrExpr;
                }

                if (isDefiniteTypeDesc(typeOrExpr.kind) || !isAllBasicLiterals(typeOrExpr)) {
                    // treat as type
                    STNode typeDesc = getTypeDescFromExpr(typeOrExpr);
                    return parseTypeBindingPatternStartsWithAmbiguousNode(typeDesc);
                }

                return typeOrExpr;
            case IDENTIFIER_TOKEN:
            case QUESTION_MARK_TOKEN:
                if (isAmbiguous(typeOrExpr) || isDefiniteTypeDesc(typeOrExpr.kind)) {
                    // treat as type
                    STNode typeDesc = getTypeDescFromExpr(typeOrExpr);
                    return parseTypeBindingPatternStartsWithAmbiguousNode(typeDesc);
                }

                return typeOrExpr;
            case EQUAL_TOKEN:
                return typeOrExpr;
            case OPEN_BRACKET_TOKEN:
                return parseTypedBindingPatternOrMemberAccess(typeOrExpr, false, allowAssignment,
                        ParserRuleContext.AMBIGUOUS_STMT);
            case OPEN_BRACE_TOKEN: // mapping binding pattern
            case ERROR_KEYWORD: // error binding pattern
                STNode typeDesc = getTypeDescFromExpr(typeOrExpr);
                return parseTypeBindingPatternStartsWithAmbiguousNode(typeDesc);
            default:
                // If its a binary operator then this can be a compound assignment statement
                if (isCompoundBinaryOperator(nextTokenKind)) {
                    return typeOrExpr;
                }

                // If the next token is part of a valid expression, then still parse it
                // as a statement that starts with an expression.
                if (isValidExprRhsStart(nextTokenKind, typeOrExpr.kind)) {
                    return typeOrExpr;
                }

                STToken token = peek();
                Solution solution =
                        recover(token, ParserRuleContext.BINDING_PATTERN_OR_EXPR_RHS, typeOrExpr, allowAssignment);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTypedBindingPatternOrExprRhs(solution.tokenKind, typeOrExpr, allowAssignment);
        }
    }

    private STNode parseTypeBindingPatternStartsWithAmbiguousNode(STNode typeDesc) {
        // switchContext(ParserRuleContext.VAR_DECL_STMT);

        // We haven't parsed the type-desc as a type-desc (parsed as an identifier/expr).
        // Therefore handle the context manually here.
        startContext(ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN);
        typeDesc = parseComplexTypeDescriptor(typeDesc, ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN, false);
        endContext();
        return parseTypedBindingPatternTypeRhs(typeDesc, ParserRuleContext.VAR_DECL_STMT);
    }

    private STNode parseTypedBPOrExprStartsWithOpenParenthesis() {
        STNode exprOrTypeDesc = parseTypedDescOrExprStartsWithOpenParenthesis();
        if (isDefiniteTypeDesc(exprOrTypeDesc.kind)) {
            return parseTypeBindingPatternStartsWithAmbiguousNode(exprOrTypeDesc);
        }

        return parseTypedBindingPatternOrExprRhs(exprOrTypeDesc, false);
    }

    private boolean isDefiniteTypeDesc(SyntaxKind kind) {
        return kind.compareTo(SyntaxKind.TYPE_DESC) >= 0 && kind.compareTo(SyntaxKind.SINGLETON_TYPE_DESC) <= 0;
    }

    private boolean isDefiniteExpr(SyntaxKind kind) {
        if (kind == SyntaxKind.QUALIFIED_NAME_REFERENCE || kind == SyntaxKind.SIMPLE_NAME_REFERENCE) {
            return false;
        }

        return kind.compareTo(SyntaxKind.BINARY_EXPRESSION) >= 0 && kind.compareTo(SyntaxKind.FAIL_EXPRESSION) <= 0;
    }

    /**
     * Parse type or expression that starts with open parenthesis. Possible options are:
     * 1) () - nil type-desc or nil-literal
     * 2) (T) - Parenthesized type-desc
     * 3) (expr) - Parenthesized expression
     * 4) (param, param, ..) - Anon function params
     *
     * @return Type-desc or expression node
     */
    private STNode parseTypedDescOrExprStartsWithOpenParenthesis() {
        STNode openParen = parseOpenParenthesis(ParserRuleContext.OPEN_PARENTHESIS);
        STToken nextToken = peek();

        if (nextToken.kind == SyntaxKind.CLOSE_PAREN_TOKEN) {
            STNode closeParen = parseCloseParenthesis();
            return parseTypeOrExprStartWithEmptyParenthesis(openParen, closeParen);
        }

        STNode typeOrExpr = parseTypeDescOrExpr();
        if (isAction(typeOrExpr)) {
            STNode closeParen = parseCloseParenthesis();
            return STNodeFactory.createBracedExpressionNode(SyntaxKind.BRACED_ACTION, openParen, typeOrExpr,
                    closeParen);
        }

        if (isExpression(typeOrExpr.kind)) {
            startContext(ParserRuleContext.BRACED_EXPR_OR_ANON_FUNC_PARAMS);
            return parseBracedExprOrAnonFuncParamRhs(peek().kind, openParen, typeOrExpr, false);
        }

        STNode closeParen = parseCloseParenthesis();
        return STNodeFactory.createParenthesisedTypeDescriptorNode(openParen, typeOrExpr, closeParen);
    }

    /**
     * Parse type-desc or expression. This method does not handle binding patterns.
     *
     * @return Type-desc node or expression node
     */
    private STNode parseTypeDescOrExpr() {
        STToken nextToken = peek();
        STNode typeOrExpr;
        switch (nextToken.kind) {
            case OPEN_PAREN_TOKEN:
                typeOrExpr = parseTypedDescOrExprStartsWithOpenParenthesis();
                break;
            case FUNCTION_KEYWORD:
                typeOrExpr = parseAnonFuncExprOrFuncTypeDesc();
                break;
            case IDENTIFIER_TOKEN:
                typeOrExpr = parseQualifiedIdentifier(ParserRuleContext.TYPE_NAME_OR_VAR_NAME);
                return parseTypeDescOrExprRhs(typeOrExpr);
            case OPEN_BRACKET_TOKEN:
                typeOrExpr = parseTypedDescOrExprStartsWithOpenBracket();
                break;
            // Can be a singleton type or expression.
            case NIL_LITERAL:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                STNode basicLiteral = parseBasicLiteral();
                return parseTypeDescOrExprRhs(basicLiteral);
            default:
                if (isValidExpressionStart(nextToken.kind, 1)) {
                    return parseActionOrExpressionInLhs(nextToken.kind, null);
                }

                return parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN);
        }

        if (isDefiniteTypeDesc(typeOrExpr.kind)) {
            return parseComplexTypeDescriptor(typeOrExpr, ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN, true);
        }

        return parseTypeDescOrExprRhs(typeOrExpr);
    }

    private boolean isExpression(SyntaxKind kind) {
        switch (kind) {
            case BASIC_LITERAL:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                return true;
            default:
                return kind.compareTo(SyntaxKind.BINARY_EXPRESSION) >= 0 &&
                        kind.compareTo(SyntaxKind.FAIL_EXPRESSION) <= 0;
        }
    }

    /**
     * Parse statement that starts with an empty parenthesis. Empty parenthesis can be
     * 1) Nil literal
     * 2) Nil type-desc
     * 3) Anon-function params
     *
     * @param openParen Open parenthesis
     * @param closeParen Close parenthesis
     * @return Parsed node
     */
    private STNode parseTypeOrExprStartWithEmptyParenthesis(STNode openParen, STNode closeParen) {
        STToken nextToken = peek();
        switch (nextToken.kind) {
            case RIGHT_DOUBLE_ARROW_TOKEN:
                STNode params = STNodeFactory.createNodeList();
                STNode anonFuncParam =
                        STNodeFactory.createImplicitAnonymousFunctionParameters(openParen, params, closeParen);
                endContext();
                return anonFuncParam;
            default:
                return STNodeFactory.createNilLiteralNode(openParen, closeParen);
        }
    }

    private STNode parseAnonFuncExprOrTypedBPWithFuncType() {
        STNode exprOrTypeDesc = parseAnonFuncExprOrFuncTypeDesc();
        if (isAction(exprOrTypeDesc) || isExpression(exprOrTypeDesc.kind)) {
            return exprOrTypeDesc;
        }

        return parseTypedBindingPatternTypeRhs(exprOrTypeDesc, ParserRuleContext.VAR_DECL_STMT);
    }

    /**
     * Parse anon-func-expr or function-type-desc, by resolving the ambiguity.
     *
     * @return Anon-func-expr or function-type-desc
     */
    private STNode parseAnonFuncExprOrFuncTypeDesc() {
        startContext(ParserRuleContext.FUNC_TYPE_DESC_OR_ANON_FUNC);
        STNode functionKeyword = parseFunctionKeyword();
        STNode funcSignature = parseFuncSignature(true);
        endContext();

        switch (peek().kind) {
            case OPEN_BRACE_TOKEN:
            case RIGHT_DOUBLE_ARROW_TOKEN:
                switchContext(ParserRuleContext.EXPRESSION_STATEMENT);
                startContext(ParserRuleContext.ANON_FUNC_EXPRESSION);
                // Anon function cannot have missing param-names. So validate it.
                funcSignature = validateAndGetFuncParams((STFunctionSignatureNode) funcSignature);

                STNode funcBody = parseAnonFuncBody(false);
                STNode annots = STNodeFactory.createEmptyNodeList();
                STNode anonFunc = STNodeFactory.createExplicitAnonymousFunctionExpressionNode(annots, functionKeyword,
                        funcSignature, funcBody);
                return parseExpressionRhs(DEFAULT_OP_PRECEDENCE, anonFunc, false, true);
            case IDENTIFIER_TOKEN:
            default:
                switchContext(ParserRuleContext.VAR_DECL_STMT);
                STNode funcTypeDesc = STNodeFactory.createFunctionTypeDescriptorNode(functionKeyword, funcSignature);
                return parseComplexTypeDescriptor(funcTypeDesc, ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN,
                        true);
        }
    }

    private STNode parseTypeDescOrExprRhs(STNode typeOrExpr) {
        SyntaxKind nextTokenKind = peek().kind;
        return parseTypeDescOrExprRhs(nextTokenKind, typeOrExpr);
    }

    private STNode parseTypeDescOrExprRhs(SyntaxKind nextTokenKind, STNode typeOrExpr) {
        STNode typeDesc;
        switch (nextTokenKind) {
            case PIPE_TOKEN:
                STToken nextNextToken = peek(2);
                if (nextNextToken.kind == SyntaxKind.EQUAL_TOKEN) {
                    return typeOrExpr;
                }

                STNode pipe = parsePipeToken();
                STNode rhsTypeDescOrExpr = parseTypeDescOrExpr();
                if (isExpression(rhsTypeDescOrExpr.kind)) {
                    return STNodeFactory.createBinaryExpressionNode(SyntaxKind.BINARY_EXPRESSION, typeOrExpr, pipe,
                            rhsTypeDescOrExpr);
                }

                typeDesc = getTypeDescFromExpr(typeOrExpr);
                rhsTypeDescOrExpr = getTypeDescFromExpr(rhsTypeDescOrExpr);
                return createUnionTypeDesc(typeDesc, pipe, rhsTypeDescOrExpr);
            case BITWISE_AND_TOKEN:
                nextNextToken = peek(2);
                if (nextNextToken.kind == SyntaxKind.EQUAL_TOKEN) {
                    return typeOrExpr;
                }

                STNode ampersand = parseBinaryOperator();
                rhsTypeDescOrExpr = parseTypeDescOrExpr();
                if (isExpression(rhsTypeDescOrExpr.kind)) {
                    return STNodeFactory.createBinaryExpressionNode(SyntaxKind.BINARY_EXPRESSION, typeOrExpr, ampersand,
                            rhsTypeDescOrExpr);
                }

                typeDesc = getTypeDescFromExpr(typeOrExpr);
                rhsTypeDescOrExpr = getTypeDescFromExpr(rhsTypeDescOrExpr);
                return createIntersectionTypeDesc(typeDesc, ampersand, rhsTypeDescOrExpr);
            case IDENTIFIER_TOKEN:
            case QUESTION_MARK_TOKEN:
                // treat as type
                // We haven't parsed the type-desc as a type-desc (parsed as an identifier/expr).
                // Therefore handle the context manually here.
                startContext(ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN);
                typeDesc = parseComplexTypeDescriptor(typeOrExpr, ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN,
                        false);
                endContext();
                return typeDesc;
            case SEMICOLON_TOKEN:
                return getTypeDescFromExpr(typeOrExpr);
            case EQUAL_TOKEN:
            case CLOSE_PAREN_TOKEN:
            case CLOSE_BRACE_TOKEN:
            case CLOSE_BRACKET_TOKEN:
            case EOF_TOKEN:
            case COMMA_TOKEN:
                return typeOrExpr;
            case OPEN_BRACKET_TOKEN:
                return parseTypedBindingPatternOrMemberAccess(typeOrExpr, false, true,
                        ParserRuleContext.AMBIGUOUS_STMT);
            case ELLIPSIS_TOKEN:
                STNode ellipsis = parseEllipsis();
                typeOrExpr = getTypeDescFromExpr(typeOrExpr);
                return STNodeFactory.createRestDescriptorNode(typeOrExpr, ellipsis);
            default:
                // If its a binary operator then this can be a compound assignment statement
                if (isCompoundBinaryOperator(nextTokenKind)) {
                    return typeOrExpr;
                }

                // If the next token is part of a valid expression, then still parse it
                // as a statement that starts with an expression.
                if (isValidExprRhsStart(nextTokenKind, typeOrExpr.kind)) {
                    return parseExpressionRhs(nextTokenKind, DEFAULT_OP_PRECEDENCE, typeOrExpr, false, false, false,
                            false);
                }

                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.BINDING_PATTERN_OR_EXPR_RHS, typeOrExpr);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTypeDescOrExprRhs(solution.tokenKind, typeOrExpr);
        }
    }

    private boolean isAmbiguous(STNode node) {
        switch (node.kind) {
            case SIMPLE_NAME_REFERENCE:
            case QUALIFIED_NAME_REFERENCE:
            case NIL_LITERAL:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
            case BRACKETED_LIST:
                return true;
            case BINARY_EXPRESSION:
                STBinaryExpressionNode binaryExpr = (STBinaryExpressionNode) node;
                if (binaryExpr.operator.kind != SyntaxKind.PIPE_TOKEN ||
                        binaryExpr.operator.kind == SyntaxKind.BITWISE_AND_TOKEN) {
                    return false;
                }
                return isAmbiguous(binaryExpr.lhsExpr) && isAmbiguous(binaryExpr.rhsExpr);
            case BRACED_EXPRESSION:
                return isAmbiguous(((STBracedExpressionNode) node).expression);
            case INDEXED_EXPRESSION:
                STIndexedExpressionNode indexExpr = (STIndexedExpressionNode) node;
                if (!isAmbiguous(indexExpr.containerExpression)) {
                    return false;
                }

                STNode keys = indexExpr.keyExpression;
                for (int i = 0; i < keys.bucketCount(); i++) {
                    STNode item = keys.childInBucket(i);
                    if (item.kind == SyntaxKind.COMMA_TOKEN) {
                        continue;
                    }

                    if (!isAmbiguous(item)) {
                        return false;
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private boolean isAllBasicLiterals(STNode node) {
        switch (node.kind) {
            case NIL_LITERAL:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                return true;
            case BINARY_EXPRESSION:
                STBinaryExpressionNode binaryExpr = (STBinaryExpressionNode) node;
                if (binaryExpr.operator.kind != SyntaxKind.PIPE_TOKEN ||
                        binaryExpr.operator.kind == SyntaxKind.BITWISE_AND_TOKEN) {
                    return false;
                }
                return isAmbiguous(binaryExpr.lhsExpr) && isAmbiguous(binaryExpr.rhsExpr);
            case BRACED_EXPRESSION:
                return isAmbiguous(((STBracedExpressionNode) node).expression);
            case BRACKETED_LIST:
                STAmbiguousCollectionNode list = (STAmbiguousCollectionNode) node;
                for (STNode member : list.members) {
                    if (member.kind == SyntaxKind.COMMA_TOKEN) {
                        continue;
                    }

                    if (!isAllBasicLiterals(member)) {
                        return false;
                    }
                }

                return true;
            case UNARY_EXPRESSION:
                STUnaryExpressionNode unaryExpr = (STUnaryExpressionNode) node;
                if (unaryExpr.unaryOperator.kind != SyntaxKind.PLUS_TOKEN &&
                        unaryExpr.unaryOperator.kind != SyntaxKind.MINUS_TOKEN) {
                    return false;
                }

                return isNumericLiteral(unaryExpr.expression);
            default:
                return false;
        }
    }

    private boolean isNumericLiteral(STNode node) {
        switch (node.kind) {
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                return true;
            default:
                return false;
        }
    }

    private STNode parseTypedDescOrExprStartsWithOpenBracket() {
        startContext(ParserRuleContext.BRACKETED_LIST);
        STNode openBracket = parseOpenBracket();
        List<STNode> members = new ArrayList<>();
        STNode memberEnd;
        while (!isEndOfListConstructor(peek().kind)) {
            STNode expr = parseTypeDescOrExpr();
            members.add(expr);

            memberEnd = parseBracketedListMemberEnd();
            if (memberEnd == null) {
                break;
            }
            members.add(memberEnd);
        }

        STNode memberNodes = STNodeFactory.createNodeList(members);
        STNode closeBracket = parseCloseBracket();
        endContext();
        return STNodeFactory.createTupleTypeDescriptorNode(openBracket, memberNodes, closeBracket);
    }

    // ------------------------ Typed binding patterns ---------------------------

    /**
     * Parse binding-patterns.
     * <p>
     * <code>
     * binding-pattern := capture-binding-pattern
     *                      | wildcard-binding-pattern
     *                      | list-binding-pattern
     *                      | mapping-binding-pattern
     *                      | functional-binding-pattern
     * <br/><br/>
     *
     * capture-binding-pattern := variable-name
     * variable-name := identifier
     * <br/><br/>
     *
     * wildcard-binding-pattern := _
     * list-binding-pattern := [ list-member-binding-patterns ]
     * <br/>
     * list-member-binding-patterns := binding-pattern (, binding-pattern)* [, rest-binding-pattern]
     *                                 | [ rest-binding-pattern ]
     * <br/><br/>
     *
     * mapping-binding-pattern := { field-binding-patterns }
     * field-binding-patterns := field-binding-pattern (, field-binding-pattern)* [, rest-binding-pattern]
     *                          | [ rest-binding-pattern ]
     * <br/>
     * field-binding-pattern := field-name : binding-pattern | variable-name
     * <br/>
     * rest-binding-pattern := ... variable-name
     *
     * <br/><br/>
     * functional-binding-pattern := functionally-constructible-type-reference ( arg-list-binding-pattern )
     * <br/>
     * arg-list-binding-pattern := positional-arg-binding-patterns [, other-arg-binding-patterns]
     *                             | other-arg-binding-patterns
     * <br/>
     * positional-arg-binding-patterns := positional-arg-binding-pattern (, positional-arg-binding-pattern)*
     * <br/>
     * positional-arg-binding-pattern := binding-pattern
     * <br/>
     * other-arg-binding-patterns := named-arg-binding-patterns [, rest-binding-pattern]
     *                              | [rest-binding-pattern]
     * <br/>
     * named-arg-binding-patterns := named-arg-binding-pattern (, named-arg-binding-pattern)*
     * <br/>
     * named-arg-binding-pattern := arg-name = binding-pattern
     *</code>
     *
     * @return binding-pattern node
     */
    private STNode parseBindingPattern() {
        STToken token = peek();
        return parseBindingPattern(token.kind);
    }

    private STNode parseBindingPattern(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case OPEN_BRACKET_TOKEN:
                return parseListBindingPattern();
            case IDENTIFIER_TOKEN:
                return parseBindingPatternStartsWithIdentifier();
            case OPEN_BRACE_TOKEN:
                return parseMappingBindingPattern();
            case ERROR_KEYWORD:
                return parseErrorBindingPattern();
            default:
                Solution sol = recover(peek(), ParserRuleContext.BINDING_PATTERN);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (sol.action == Action.REMOVE) {
                    return sol.recoveredNode;
                }

                return parseBindingPattern(sol.tokenKind);
        }
    }

    private STNode parseBindingPatternStartsWithIdentifier() {
        STNode argNameOrBindingPattern =
                parseQualifiedIdentifier(ParserRuleContext.BINDING_PATTERN_STARTING_IDENTIFIER);
        STToken secondToken = peek();
        if (secondToken.kind == SyntaxKind.OPEN_PAREN_TOKEN) {
            startContext(ParserRuleContext.FUNCTIONAL_BINDING_PATTERN);
            return parseFunctionalBindingPattern(argNameOrBindingPattern);
        }

        if (argNameOrBindingPattern.kind != SyntaxKind.SIMPLE_NAME_REFERENCE) {
            STNode identifier = SyntaxErrors.createMissingTokenWithDiagnostics(SyntaxKind.IDENTIFIER_TOKEN);
            identifier = SyntaxErrors.cloneWithLeadingInvalidNodeMinutiae(identifier, argNameOrBindingPattern);
            return createCaptureOrWildcardBP(identifier);
        }

        return createCaptureOrWildcardBP(((STSimpleNameReferenceNode) argNameOrBindingPattern).name);
    }

    private STNode createCaptureOrWildcardBP(STNode varName) {
        STNode bindingPattern;
        if (isWildcardBP(varName)) {
            bindingPattern = getWildcardBindingPattern(varName);
        } else {
            bindingPattern = STNodeFactory.createCaptureBindingPatternNode(varName);
        }
        return bindingPattern;
    }

    /**
     * Parse list-binding-patterns.
     * <p>
     * <code>
     * list-binding-pattern := [ list-member-binding-patterns ]
     * <br/>
     * list-member-binding-patterns := binding-pattern (, binding-pattern)* [, rest-binding-pattern]
     *                                | [ rest-binding-pattern ]
     * </code>
     *
     * @return list-binding-pattern node
     */
    private STNode parseListBindingPattern() {
        startContext(ParserRuleContext.LIST_BINDING_PATTERN);
        STNode openBracket = parseOpenBracket();
        List<STNode> bindingPatternsList = new ArrayList<>();
        STNode listBindingPattern = parseListBindingPattern(openBracket, bindingPatternsList);
        endContext();
        return listBindingPattern;
    }

    private STNode parseListBindingPattern(STNode openBracket, List<STNode> bindingPatternsList) {
        if (isEndOfListBindingPattern(peek().kind) && bindingPatternsList.size() == 0) {
            // Handle empty list binding pattern
            STNode closeBracket = parseCloseBracket();
            STNode restBindingPattern = STNodeFactory.createEmptyNode();
            STNode bindingPatternsNode = STNodeFactory.createNodeList(bindingPatternsList);
            return STNodeFactory.createListBindingPatternNode(openBracket, bindingPatternsNode, restBindingPattern,
                    closeBracket);
        }
        STNode listBindingPatternMember = parseListBindingPatternMember();
        bindingPatternsList.add(listBindingPatternMember);
        STNode listBindingPattern = parseListBindingPattern(openBracket, listBindingPatternMember, bindingPatternsList);
        return listBindingPattern;
    }

    private STNode parseListBindingPattern(STNode openBracket, STNode firstMember, List<STNode> bindingPatterns) {
        STNode member = firstMember;
        // parsing the main chunk of list-binding-pattern
        STToken token = peek(); // get next valid token
        STNode listBindingPatternRhs = null;
        while (!isEndOfListBindingPattern(token.kind) && member.kind != SyntaxKind.REST_BINDING_PATTERN) {
            listBindingPatternRhs = parseListBindingPatternMemberRhs(token.kind);
            if (listBindingPatternRhs == null) {
                break;
            }

            bindingPatterns.add(listBindingPatternRhs);
            member = parseListBindingPatternMember();
            bindingPatterns.add(member);
            token = peek();
        }

        // separating out the rest-binding-pattern
        STNode restBindingPattern;
        if (member.kind == SyntaxKind.REST_BINDING_PATTERN) {
            restBindingPattern = bindingPatterns.remove(bindingPatterns.size() - 1);
        } else {
            restBindingPattern = STNodeFactory.createEmptyNode();
        }

        STNode closeBracket = parseCloseBracket();
        STNode bindingPatternsNode = STNodeFactory.createNodeList(bindingPatterns);
        return STNodeFactory.createListBindingPatternNode(openBracket, bindingPatternsNode, restBindingPattern,
                closeBracket);
    }

    private STNode parseListBindingPatternMemberRhs() {
        return parseListBindingPatternMemberRhs(peek().kind);
    }

    private STNode parseListBindingPatternMemberRhs(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACKET_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.LIST_BINDING_PATTERN_MEMBER_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseListBindingPatternMemberRhs(solution.tokenKind);
        }
    }

    private boolean isEndOfListBindingPattern(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case CLOSE_BRACKET_TOKEN:
            case EOF_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse list-binding-pattern member.
     * <p>
     * <code>
     * list-binding-pattern := [ list-member-binding-patterns ]
     * <br/>
     * list-member-binding-patterns := binding-pattern (, binding-pattern)* [, rest-binding-pattern]
     *                                  | [ rest-binding-pattern ]
     * </code>
     *
     * @return List binding pattern member
     */
    private STNode parseListBindingPatternMember() {
        STToken token = peek();
        return parseListBindingPatternMember(token.kind);
    }

    private STNode parseListBindingPatternMember(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case ELLIPSIS_TOKEN:
                return parseRestBindingPattern();
            case OPEN_BRACKET_TOKEN:
            case IDENTIFIER_TOKEN:
            case OPEN_BRACE_TOKEN:
            case ERROR_KEYWORD:
                return parseBindingPattern();
            default:
                Solution sol = recover(peek(), ParserRuleContext.LIST_BINDING_PATTERN_MEMBER);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (sol.action == Action.REMOVE) {
                    return sol.recoveredNode;
                }

                return parseListBindingPatternMember(sol.tokenKind);
        }
    }

    /**
     * Parse rest binding pattern.
     * <p>
     * <code>
     * rest-binding-pattern := ... variable-name
     * </code>
     * 
     * @return Rest binding pattern node
     */
    private STNode parseRestBindingPattern() {
        startContext(ParserRuleContext.REST_BINDING_PATTERN);
        STNode ellipsis = parseEllipsis();
        STNode varName = parseVariableName();
        endContext();

        STSimpleNameReferenceNode simpleNameReferenceNode =
                (STSimpleNameReferenceNode) STNodeFactory.createSimpleNameReferenceNode(varName);
        return STNodeFactory.createRestBindingPatternNode(ellipsis, simpleNameReferenceNode);
    }

    /**
     * Parse Typed-binding-pattern.
     * <p>
     * <code>
     * typed-binding-pattern := inferable-type-descriptor binding-pattern
     * <br/><br/>
     * inferable-type-descriptor := type-descriptor | var
     * </code>
     *
     * @return Typed binding pattern node
     */
    private STNode parseTypedBindingPattern(ParserRuleContext context) {
        STNode typeDesc = parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN, true, false);
        STNode typeBindingPattern = parseTypedBindingPatternTypeRhs(typeDesc, context);
        return typeBindingPattern;
    }

    /**
     * Parse mapping-binding-patterns.
     * <p>
     * <code>
     * mapping-binding-pattern := { field-binding-patterns }
     * <br/><br/>
     * field-binding-patterns := field-binding-pattern (, field-binding-pattern)* [, rest-binding-pattern]
     *                          | [ rest-binding-pattern ]
     * <br/><br/>
     * field-binding-pattern := field-name : binding-pattern | variable-name
     * </code>
     *
     * @return mapping-binding-pattern node
     */
    private STNode parseMappingBindingPattern() {
        startContext(ParserRuleContext.MAPPING_BINDING_PATTERN);
        STNode openBrace = parseOpenBrace();

        STToken token = peek();
        if (isEndOfMappingBindingPattern(token.kind)) {
            STNode closeBrace = parseCloseBrace();
            STNode bindingPatternsNode = STNodeFactory.createEmptyNodeList();
            STNode restBindingPattern = STNodeFactory.createEmptyNode();
            endContext();
            return STNodeFactory.createMappingBindingPatternNode(openBrace, bindingPatternsNode, restBindingPattern,
                    closeBrace);
        }

        List<STNode> bindingPatterns = new ArrayList<>();
        STNode prevMember = parseMappingBindingPatternMember();
        if (prevMember.kind != SyntaxKind.REST_BINDING_PATTERN) {
            bindingPatterns.add(prevMember);
        }
        return parseMappingBindingPattern(openBrace, bindingPatterns, prevMember);
    }

    private STNode parseMappingBindingPattern(STNode openBrace, List<STNode> bindingPatterns, STNode prevMember) {
        STToken token = peek(); // get next valid token
        STNode mappingBindingPatternRhs = null;
        while (!isEndOfMappingBindingPattern(token.kind) && prevMember.kind != SyntaxKind.REST_BINDING_PATTERN) {
            mappingBindingPatternRhs = parseMappingBindingPatternEnd(token.kind);
            if (mappingBindingPatternRhs == null) {
                break;
            }

            bindingPatterns.add(mappingBindingPatternRhs);
            prevMember = parseMappingBindingPatternMember();
            if (prevMember.kind == SyntaxKind.REST_BINDING_PATTERN) {
                break;
            }
            bindingPatterns.add(prevMember);
            token = peek();
        }

        // Separating out the rest-binding-pattern
        STNode restBindingPattern;
        if (prevMember.kind == SyntaxKind.REST_BINDING_PATTERN) {
            restBindingPattern = prevMember;
        } else {
            restBindingPattern = STNodeFactory.createEmptyNode();
        }

        STNode closeBrace = parseCloseBrace();
        STNode bindingPatternsNode = STNodeFactory.createNodeList(bindingPatterns);
        endContext();
        return STNodeFactory.createMappingBindingPatternNode(openBrace, bindingPatternsNode, restBindingPattern,
                closeBrace);
    }

    /**
     * Parse mapping-binding-pattern entry.
     * <p>
     * <code>
     * mapping-binding-pattern := { field-binding-patterns }
     * <br/><br/>
     * field-binding-patterns := field-binding-pattern (, field-binding-pattern)* [, rest-binding-pattern]
     *                          | [ rest-binding-pattern ]
     * <br/><br/>
     * field-binding-pattern := field-name : binding-pattern
     *                          | variable-name
     * </code>
     *
     * @return mapping-binding-pattern node
     */
    private STNode parseMappingBindingPatternMember() {
        STToken token = peek();
        switch (token.kind) {
            case ELLIPSIS_TOKEN:
                return parseRestBindingPattern();
            default:
                return parseFieldBindingPattern();
        }
    }

    private STNode parseMappingBindingPatternEnd() {
        return parseMappingBindingPatternEnd(peek().kind);
    }

    private STNode parseMappingBindingPatternEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACE_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.MAPPING_BINDING_PATTERN_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseMappingBindingPatternEnd(solution.tokenKind);
        }
    }

    private STNode parseFieldBindingPattern() {
        return parseFieldBindingPattern(peek().kind);
    }

    /**
     * Parse field-binding-pattern.
     * <code>field-binding-pattern := field-name : binding-pattern | varname</code>
     *
     * @return field-binding-pattern node
     */
    private STNode parseFieldBindingPattern(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN:
                STNode identifier = parseIdentifier(ParserRuleContext.FIELD_BINDING_PATTERN_NAME);
                STNode fieldBindingPattern = parseFieldBindingPattern(identifier);
                return fieldBindingPattern;
            default:
                Solution solution = recover(peek(), ParserRuleContext.FIELD_BINDING_PATTERN_NAME);

                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseFieldBindingPattern(solution.tokenKind);
        }
    }

    private STNode parseFieldBindingPattern(STNode identifier) {
        STNode simpleNameReference = STNodeFactory.createSimpleNameReferenceNode(identifier);

        if (peek().kind != SyntaxKind.COLON_TOKEN) {
            return STNodeFactory.createFieldBindingPatternVarnameNode(simpleNameReference);
        }

        STNode colon = parseColon();
        STNode bindingPattern = parseBindingPattern();

        return STNodeFactory.createFieldBindingPatternFullNode(simpleNameReference, colon, bindingPattern);
    }

    private boolean isEndOfMappingBindingPattern(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case CLOSE_BRACE_TOKEN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Parse error binding pattern node.
     * <p>
     * <code>functional-binding-pattern := error ( arg-list-binding-pattern )</code>
     * 
     * @return Error binding pattern node.
     */
    private STNode parseErrorBindingPattern() {
        startContext(ParserRuleContext.FUNCTIONAL_BINDING_PATTERN);
        STNode typeDesc = parseErrorKeyword();
        return parseFunctionalBindingPattern(typeDesc);
    }

    /**
     * Parse functional binding pattern.
     * <p>
     * <code>
     * functional-binding-pattern := functionally-constructible-type-reference ( arg-list-binding-pattern )
     * <br/><br/>
     * functionally-constructible-type-reference := error | type-reference
     * </code>
     * 
     * @param typeDesc Functionally constructible type reference
     * @return Functional binding pattern node.
     */
    private STNode parseFunctionalBindingPattern(STNode typeDesc) {
        STNode openParenthesis = parseOpenParenthesis(ParserRuleContext.ARG_LIST_START);
        STNode argListBindingPatterns = parseArgListBindingPatterns();
        STNode closeParenthesis = parseCloseParenthesis();
        endContext();
        return STNodeFactory.createFunctionalBindingPatternNode(typeDesc, openParenthesis, argListBindingPatterns,
                closeParenthesis);
    }

    private STNode parseArgListBindingPatterns() {
        List<STNode> argListBindingPatterns = new ArrayList<>();
        SyntaxKind lastValidArgKind = SyntaxKind.CAPTURE_BINDING_PATTERN;
        STToken nextToken = peek();

        if (isEndOfParametersList(nextToken.kind)) {
            return STNodeFactory.createNodeList(argListBindingPatterns);
        }
        argListBindingPatterns.add(parseArgBindingPattern());

        nextToken = peek();
        while (!isEndOfParametersList(nextToken.kind)) {
            STNode argEnd = parseArgsBindingPatternEnd(nextToken.kind);
            if (argEnd == null) {
                // null marks the end of args
                break;
            }

            nextToken = peek();
            STNode currentArg = parseArgBindingPattern(nextToken.kind);
            DiagnosticErrorCode errorCode = validateArgBindingPatternOrder(lastValidArgKind, currentArg.kind);
            if (errorCode == null) {
                argListBindingPatterns.add(argEnd);
                argListBindingPatterns.add(currentArg);
                lastValidArgKind = currentArg.kind;
            } else {
                updateLastNodeInListWithInvalidNode(argListBindingPatterns, argEnd, null);
                updateLastNodeInListWithInvalidNode(argListBindingPatterns, currentArg, errorCode);
            }

            nextToken = peek();
        }
        return STNodeFactory.createNodeList(argListBindingPatterns);
    }

    private STNode parseArgsBindingPatternEnd() {
        STToken nextToken = peek();
        return parseArgsBindingPatternEnd(nextToken.kind);
    }

    private STNode parseArgsBindingPatternEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_PAREN_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.ARG_BINDING_PATTERN_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseArgsBindingPatternEnd(solution.tokenKind);
        }
    }

    /**
     * Parse arg binding pattern.
     * <p>
     * <code>
     * arg-list-binding-pattern := positional-arg-binding-patterns [, other-arg-binding-patterns] 
     *                             | other-arg-binding-patterns
     * <br/><br/>
     * positional-arg-binding-patterns := positional-arg-binding-pattern (, positional-arg-binding-pattern)*
     * <br/><br/>
     * positional-arg-binding-pattern := binding-pattern
     * <br/><br/>
     * other-arg-binding-patterns := named-arg-binding-patterns [, rest-binding-pattern] | [rest-binding-pattern]
     * <br/><br/>
     * named-arg-binding-patterns := named-arg-binding-pattern (, named-arg-binding-pattern)*
     * <br/><br/>
     * named-arg-binding-pattern := arg-name = binding-pattern
     * </code>
     * 
     * @return Arg binding pattern
     */
    private STNode parseArgBindingPattern() {
        STToken nextToken = peek();
        return parseArgBindingPattern(nextToken.kind);
    }

    private STNode parseArgBindingPattern(SyntaxKind kind) {
        switch (kind) {
            case ELLIPSIS_TOKEN:
                return parseRestBindingPattern();
            case IDENTIFIER_TOKEN:
                // Identifier can means two things: either its a named-arg, or just an expression.
                return parseNamedOrPositionalArgBindingPattern(kind);
            case OPEN_BRACKET_TOKEN:
            case OPEN_BRACE_TOKEN:
            case ERROR_KEYWORD:
                return parseBindingPattern();

            default:
                Solution solution = recover(peek(), ParserRuleContext.ARG_BINDING_PATTERN);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseArgBindingPattern(solution.tokenKind);
        }
    }

    private STNode parseNamedOrPositionalArgBindingPattern(SyntaxKind nextTokenKind) {
        STNode argNameOrBindingPattern = parseQualifiedIdentifier(ParserRuleContext.ARG_BINDING_PATTERN_START_IDENT);
        STToken secondToken = peek();
        switch (secondToken.kind) {
            case EQUAL_TOKEN:
                // TODO: Handle qualified-identifier.
                STNode equal = parseAssignOp();
                STNode bindingPattern = parseBindingPattern();
                return STNodeFactory.createNamedArgBindingPatternNode(argNameOrBindingPattern, equal, bindingPattern);
            case OPEN_PAREN_TOKEN:
                return parseFunctionalBindingPattern(argNameOrBindingPattern);
            case COMMA_TOKEN:
            case CLOSE_PAREN_TOKEN:
            default:
                return createCaptureOrWildcardBP(argNameOrBindingPattern);
        }
    }

    private DiagnosticErrorCode validateArgBindingPatternOrder(SyntaxKind prevArgKind, SyntaxKind currentArgKind) {
        DiagnosticErrorCode errorCode = null;
        switch (prevArgKind) {
            case CAPTURE_BINDING_PATTERN:
            case LIST_BINDING_PATTERN:
            case MAPPING_BINDING_PATTERN:
                break;
            case NAMED_ARG_BINDING_PATTERN:
                if (currentArgKind != SyntaxKind.NAMED_ARG_BINDING_PATTERN &&
                        currentArgKind != SyntaxKind.REST_BINDING_PATTERN) {
                    errorCode = DiagnosticErrorCode.ERROR_NAMED_ARG_FOLLOWED_BY_POSITIONAL_ARG;
                }
                break;
            case REST_BINDING_PATTERN:
                // Nothing is allowed after a rest arg
                errorCode = DiagnosticErrorCode.ERROR_ARG_FOLLOWED_BY_REST_ARG;
                break;
            default:
                // This line should never get reached
                throw new IllegalStateException("Invalid SyntaxKind in an argument");
        }
        return errorCode;
    }

    // ------------------------ Typed binding patterns ---------------------------

    /*
     * This parses Typed binding patterns and deals with ambiguity between types,
     * and binding patterns. An example is 'T[a]'.
     * The ambiguity lies in between:
     * 1) Array Type
     * 2) List binding pattern
     * 3) Member access expression.
     */

    /**
     * Parse the component after the type-desc, of a typed-binding-pattern.
     *
     * @param typeDesc Starting type-desc of the typed-binding-pattern
     * @return Typed-binding pattern
     */
    private STNode parseTypedBindingPatternTypeRhs(STNode typeDesc, ParserRuleContext context) {
        STToken nextToken = peek();
        return parseTypedBindingPatternTypeRhs(nextToken.kind, typeDesc, context, true);
    }

    private STNode parseTypedBindingPatternTypeRhs(STNode typeDesc, ParserRuleContext context, boolean isRoot) {
        STToken nextToken = peek();
        return parseTypedBindingPatternTypeRhs(nextToken.kind, typeDesc, context, isRoot);
    }

    private STNode parseTypedBindingPatternTypeRhs(SyntaxKind nextTokenKind, STNode typeDesc, ParserRuleContext context,
                                                   boolean isRoot) {
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN: // Capture/Functional binding pattern: T x, T(..)
            case OPEN_BRACE_TOKEN: // Map binding pattern: T { }
            case ERROR_KEYWORD: // Functional binding pattern: T error(..)
                STNode bindingPattern = parseBindingPattern(nextTokenKind);
                return STNodeFactory.createTypedBindingPatternNode(typeDesc, bindingPattern);
            case OPEN_BRACKET_TOKEN:
                // T[..] ..
                STNode typedBindingPattern = parseTypedBindingPatternOrMemberAccess(typeDesc, true, true, context);
                assert typedBindingPattern.kind == SyntaxKind.TYPED_BINDING_PATTERN;
                return typedBindingPattern;
            case CLOSE_PAREN_TOKEN:
            case COMMA_TOKEN:
            case CLOSE_BRACKET_TOKEN:
            case CLOSE_BRACE_TOKEN:
                if (!isRoot) {
                    return typeDesc;
                }
                // fall through
            default:
                Solution solution =
                        recover(peek(), ParserRuleContext.TYPED_BINDING_PATTERN_TYPE_RHS, typeDesc, context, isRoot);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTypedBindingPatternTypeRhs(solution.tokenKind, typeDesc, context, isRoot);
        }
    }

    /**
     * Parse typed-binding pattern with list, array-type-desc, or member-access-expr.
     *
     * @param typeDescOrExpr Type desc or the expression at the start
     * @param isTypedBindingPattern Is this is a typed-binding-pattern.
     * @return Parsed node
     */
    private STNode parseTypedBindingPatternOrMemberAccess(STNode typeDescOrExpr, boolean isTypedBindingPattern,
                                                          boolean allowAssignment, ParserRuleContext context) {
        startContext(ParserRuleContext.BRACKETED_LIST);
        STNode openBracket = parseOpenBracket();

        // If the bracketed list is empty, i.e: T[] then T is an array-type-desc, and [] could be anything.
        if (isBracketedListEnd(peek().kind)) {
            return parseAsArrayTypeDesc(typeDescOrExpr, openBracket, STNodeFactory.createEmptyNode(), context);
        }

        // Parse first member
        STNode member = parseBracketedListMember(isTypedBindingPattern);
        SyntaxKind currentNodeType = getBracketedListNodeType(member);
        switch (currentNodeType) {
            case ARRAY_TYPE_DESC:
                STNode typedBindingPattern = parseAsArrayTypeDesc(typeDescOrExpr, openBracket, member, context);
                return typedBindingPattern;
            case LIST_BINDING_PATTERN:
                // If the member type was figured out as a binding pattern, then parse the
                // remaining members as binding patterns and be done with it.
                STNode bindingPattern = parseAsListBindingPattern(openBracket, new ArrayList<>(), member, false);
                STNode typeDesc = getTypeDescFromExpr(typeDescOrExpr);
                return STNodeFactory.createTypedBindingPatternNode(typeDesc, bindingPattern);
            case INDEXED_EXPRESSION:
                return parseAsMemberAccessExpr(typeDescOrExpr, openBracket, member);
            case NONE:
            default:
                // Ideally we would reach here, only if the parsed member was a name-reference.
                // i.e: T[a
                break;
        }

        // Parse separator
        STNode memberEnd = parseBracketedListMemberEnd();
        if (memberEnd != null) {
            // If there are more than one member, then its definitely a binding pattern.
            List<STNode> memberList = new ArrayList<>();
            memberList.add(member);
            memberList.add(memberEnd);
            STNode bindingPattern = parseAsListBindingPattern(openBracket, memberList);
            STNode typeDesc = getTypeDescFromExpr(typeDescOrExpr);
            return STNodeFactory.createTypedBindingPatternNode(typeDesc, bindingPattern);
        }

        // We reach here if it is still ambiguous, even after parsing the full list.
        // That is: T[a]. This could be:
        // 1) Array Type Desc
        // 2) Member access on LHS
        // 3) Typed-binding-pattern
        STNode closeBracket = parseCloseBracket();
        endContext();
        return parseTypedBindingPatternOrMemberAccessRhs(typeDescOrExpr, openBracket, member, closeBracket,
                isTypedBindingPattern, allowAssignment, context);
    }

    private STNode parseAsMemberAccessExpr(STNode typeNameOrExpr, STNode openBracket, STNode member) {
        member = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, member, false, true);
        STNode closeBracket = parseCloseBracket();
        endContext();
        STNode keyExpr = STNodeFactory.createNodeList(member);
        STNode memberAccessExpr =
                STNodeFactory.createIndexedExpressionNode(typeNameOrExpr, openBracket, keyExpr, closeBracket);
        return parseExpressionRhs(DEFAULT_OP_PRECEDENCE, memberAccessExpr, false, false);
    }

    private boolean isBracketedListEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACKET_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode parseBracketedListMember(boolean isTypedBindingPattern) {
        return parseBracketedListMember(peek().kind, isTypedBindingPattern);
    }

    /**
     * Parse a member of an ambiguous bracketed list. This member could be:
     * 1) Array length
     * 2) Key expression of a member-access-expr
     * 3) A member-binding pattern of a list-binding-pattern.
     *
     * @param nextTokenKind Kind of the next token
     * @param isTypedBindingPattern Is this in a definite typed-binding pattern
     * @return Parsed member node
     */
    private STNode parseBracketedListMember(SyntaxKind nextTokenKind, boolean isTypedBindingPattern) {
        switch (nextTokenKind) {
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case ASTERISK_TOKEN:
            case STRING_LITERAL:
                return parseBasicLiteral();
            case CLOSE_BRACKET_TOKEN:
                return STNodeFactory.createEmptyNode();
            case OPEN_BRACE_TOKEN:// mapping-binding-pattern
            case ERROR_KEYWORD: // functional-binding-pattern
            case ELLIPSIS_TOKEN: // rest binding pattern
            case OPEN_BRACKET_TOKEN: // list-binding-pattern
                return parseStatementStartBracketedListMember();
            case IDENTIFIER_TOKEN:
                if (isTypedBindingPattern) {
                    STNode identifier = parseQualifiedIdentifier(ParserRuleContext.VARIABLE_REF);
                    nextTokenKind = peek().kind;
                    if (nextTokenKind == SyntaxKind.OPEN_PAREN_TOKEN) {
                        // error|T (args) --> functional-binding-pattern
                        return parseListBindingPatternMember();
                    }

                    return identifier;
                }
                break;
            default:
                if (!isTypedBindingPattern && isValidExpressionStart(nextTokenKind, 1)) {
                    break;
                }

                ParserRuleContext recoverContext =
                        isTypedBindingPattern ? ParserRuleContext.LIST_BINDING_MEMBER_OR_ARRAY_LENGTH
                                : ParserRuleContext.BRACKETED_LIST_MEMBER;
                Solution solution = recover(peek(), recoverContext, isTypedBindingPattern);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseBracketedListMember(solution.tokenKind, isTypedBindingPattern);
        }

        STNode expr = parseExpression();
        if (isWildcardBP(expr)) {
            return getWildcardBindingPattern(expr);
        }
        if (expr.kind == SyntaxKind.SIMPLE_NAME_REFERENCE || expr.kind == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
            nextTokenKind = peek().kind;
            if (nextTokenKind == SyntaxKind.OPEN_PAREN_TOKEN) {
                // error|T (args) --> functional-binding-pattern
                return parseListBindingPatternMember();
            }
        }

        // we don't know which one
        return expr;
    }

    /**
     * Treat the current node as an array, and parse the remainder of the binding pattern.
     *
     * @param typeDesc Type-desc
     * @param openBracket Open bracket
     * @param member Member
     * @return Parsed node
     */
    private STNode parseAsArrayTypeDesc(STNode typeDesc, STNode openBracket, STNode member, ParserRuleContext context) {
        // In ambiguous scenarios typDesc: T[a] may have parsed as an indexed expression.
        // Therefore make an array-type-desc out of it.
        typeDesc = getTypeDescFromExpr(typeDesc);
        typeDesc = validateForUsageOfVar(typeDesc);
        STNode closeBracket = parseCloseBracket();
        endContext();
        return parseTypedBindingPatternOrMemberAccessRhs(typeDesc, openBracket, member, closeBracket, true, true,
                context);
    }

    private STNode parseBracketedListMemberEnd() {
        return parseBracketedListMemberEnd(peek().kind);
    }

    private STNode parseBracketedListMemberEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                return parseComma();
            case CLOSE_BRACKET_TOKEN:
                return null;
            default:
                Solution solution = recover(peek(), ParserRuleContext.BRACKETED_LIST_MEMBER_END);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseBracketedListMemberEnd(solution.tokenKind);
        }
    }

    /**
     * We reach here to break ambiguity of T[a]. This could be:
     * 1) Array Type Desc
     * 2) Member access on LHS
     * 3) Typed-binding-pattern
     *
     * @param typeDescOrExpr Type name or the expr that precede the open-bracket.
     * @param openBracket Open bracket
     * @param member Member
     * @param closeBracket Open bracket
     * @param isTypedBindingPattern Is this is a typed-binding-pattern.
     * @return Specific node that matches to T[a], after solving ambiguity.
     */
    private STNode parseTypedBindingPatternOrMemberAccessRhs(STNode typeDescOrExpr, STNode openBracket, STNode member,
                                                             STNode closeBracket, boolean isTypedBindingPattern,
                                                             boolean allowAssignment, ParserRuleContext context) {
        STToken nextToken = peek();
        return parseTypedBindingPatternOrMemberAccessRhs(nextToken.kind, typeDescOrExpr, openBracket, member,
                closeBracket, isTypedBindingPattern, allowAssignment, context);
    }

    private STNode parseTypedBindingPatternOrMemberAccessRhs(SyntaxKind nextTokenKind, STNode typeDescOrExpr,
                                                             STNode openBracket, STNode member, STNode closeBracket,
                                                             boolean isTypedBindingPattern, boolean allowAssignment,
                                                             ParserRuleContext context) {
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN: // Capture binding pattern: T[a] b
            case OPEN_BRACE_TOKEN: // Map binding pattern: T[a] { }
            case ERROR_KEYWORD: // Functional binding pattern: T[a] error(..)
                // T[a] is definitely a type-desc.
                STNode typeDesc = getTypeDescFromExpr(typeDescOrExpr);
                STNode arrayTypeDesc = getArrayTypeDesc(openBracket, member, closeBracket, typeDesc);
                return parseTypedBindingPatternTypeRhs(arrayTypeDesc, context);
            case OPEN_BRACKET_TOKEN: // T[a][b]..
                if (isTypedBindingPattern) {
                    typeDesc = getTypeDescFromExpr(typeDescOrExpr);
                    arrayTypeDesc = createArrayTypeDesc(typeDesc, openBracket, member, closeBracket);
                    return parseTypedBindingPatternTypeRhs(arrayTypeDesc, context);
                }

                // T[a] could be member-access or array-type-desc.
                STNode keyExpr = STNodeFactory.createNodeList(member);
                STNode expr =
                        STNodeFactory.createIndexedExpressionNode(typeDescOrExpr, openBracket, keyExpr, closeBracket);
                return parseTypedBindingPatternOrMemberAccess(expr, false, allowAssignment, context);
            case QUESTION_MARK_TOKEN:
                // T[a]? --> Treat T[a] as array type
                typeDesc = getTypeDescFromExpr(typeDescOrExpr);
                arrayTypeDesc = getArrayTypeDesc(openBracket, member, closeBracket, typeDesc);
                typeDesc = parseComplexTypeDescriptor(arrayTypeDesc,
                        ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN, true);
                return parseTypedBindingPatternTypeRhs(typeDesc, context);
            case PIPE_TOKEN:
            case BITWISE_AND_TOKEN:
                // "T[a] | R.." or "T[a] & R.."
                return parseComplexTypeDescInTypedBindingPattern(typeDescOrExpr, openBracket, member, closeBracket,
                        context, isTypedBindingPattern);
            case IN_KEYWORD:
                // "in" keyword is only valid for for-each stmt.
                if (context != ParserRuleContext.FOREACH_STMT && context != ParserRuleContext.FROM_CLAUSE) {
                    break;
                }
                return createTypedBindingPattern(typeDescOrExpr, openBracket, member, closeBracket);
            case EQUAL_TOKEN: // T[a] =
                if (context == ParserRuleContext.FOREACH_STMT || context == ParserRuleContext.FROM_CLAUSE) {
                    // equal and semi-colon are not valid terminators for typed-binding-pattern
                    // in foreach-stmt and from-clause. Therefore recover.
                    break;
                }

                // could be member-access or typed-binding-pattern.
                if (isTypedBindingPattern || !allowAssignment || !isValidLVExpr(typeDescOrExpr)) {
                    return createTypedBindingPattern(typeDescOrExpr, openBracket, member, closeBracket);
                }

                keyExpr = STNodeFactory.createNodeList(member);
                typeDescOrExpr = getExpression(typeDescOrExpr);
                return STNodeFactory.createIndexedExpressionNode(typeDescOrExpr, openBracket, keyExpr, closeBracket);
            case SEMICOLON_TOKEN: // T[a];
                if (context == ParserRuleContext.FOREACH_STMT || context == ParserRuleContext.FROM_CLAUSE) {
                    // equal and semi-colon are not valid terminators for typed-binding-pattern
                    // in foreach-stmt and from-clause. Therefore recover.
                    break;
                }

                return createTypedBindingPattern(typeDescOrExpr, openBracket, member, closeBracket);
            case CLOSE_BRACE_TOKEN: // T[a]}
            case COMMA_TOKEN:// T[a],
                if (context == ParserRuleContext.AMBIGUOUS_STMT) {
                    keyExpr = STNodeFactory.createNodeList(member);
                    return STNodeFactory.createIndexedExpressionNode(typeDescOrExpr, openBracket, keyExpr,
                            closeBracket);
                }
                // fall through
            default:
                if (isValidExprRhsStart(nextTokenKind, closeBracket.kind)) {
                    // We come here if T[a] is in some expression context.
                    keyExpr = STNodeFactory.createNodeList(member);
                    typeDescOrExpr = getExpression(typeDescOrExpr);
                    return STNodeFactory.createIndexedExpressionNode(typeDescOrExpr, openBracket, keyExpr,
                            closeBracket);
                }

                break;
        }

        Solution solution = recover(peek(), ParserRuleContext.BRACKETED_LIST_RHS, typeDescOrExpr, openBracket, member,
                closeBracket, isTypedBindingPattern, allowAssignment, context);

        // If the parser recovered by inserting a token, then try to re-parse the same
        // rule with the inserted token. This is done to pick the correct branch
        // to continue the parsing.
        if (solution.action == Action.REMOVE) {
            return solution.recoveredNode;
        }

        return parseTypedBindingPatternOrMemberAccessRhs(solution.tokenKind, typeDescOrExpr, openBracket, member,
                closeBracket, isTypedBindingPattern, allowAssignment, context);
    }

    private STNode createTypedBindingPattern(STNode typeDescOrExpr, STNode openBracket, STNode member,
                                             STNode closeBracket) {
        STNode bindingPatterns;
        if (isEmpty(member)) {
            bindingPatterns = STNodeFactory.createEmptyNodeList();
        } else {
            STNode bindingPattern = getBindingPattern(member);
            bindingPatterns = STNodeFactory.createNodeList(bindingPattern);
        }

        STNode restBindingPattern = STNodeFactory.createEmptyNode();
        STNode bindingPattern = STNodeFactory.createListBindingPatternNode(openBracket, bindingPatterns,
                restBindingPattern, closeBracket);
        STNode typeDesc = getTypeDescFromExpr(typeDescOrExpr);
        return STNodeFactory.createTypedBindingPatternNode(typeDesc, bindingPattern);
    }

    /**
     * Parse a union or intersection type-desc/binary-expression that involves ambiguous
     * bracketed list in lhs.
     * <p>
     * e.g: <code>(T[a] & R..)</code> or <code>(T[a] | R.. )</code>
     * <p>
     * Complexity occurs in scenarios such as <code>T[a] |/& R[b]</code>. If the token after this
     * is another binding-pattern, then <code>(T[a] |/& R[b])</code> becomes the type-desc. However,
     * if the token follows this is an equal or semicolon, then <code>(T[a] |/& R)</code> becomes
     * the type-desc, and <code>[b]</code> becomes the binding pattern.
     *
     * @param typeDescOrExpr Type desc or the expression
     * @param openBracket Open bracket
     * @param member Member
     * @param closeBracket Close bracket
     * @param context COntext in which the typed binding pattern occurs
     * @return Parsed node
     */
    private STNode parseComplexTypeDescInTypedBindingPattern(STNode typeDescOrExpr, STNode openBracket, STNode member,
                                                             STNode closeBracket, ParserRuleContext context,
                                                             boolean isTypedBindingPattern) {
        STNode pipeOrAndToken = parseUnionOrIntersectionToken();
        STNode typedBindingPatternOrExpr = parseTypedBindingPatternOrExpr(false);

        if (isTypedBindingPattern || typedBindingPatternOrExpr.kind == SyntaxKind.TYPED_BINDING_PATTERN) {
            // Treat T[a] as an array-type-desc. But we dont know what R is.
            // R could be R[b] or R[b, c]
            STNode lhsTypeDesc = getTypeDescFromExpr(typeDescOrExpr);
            lhsTypeDesc = getArrayTypeDesc(openBracket, member, closeBracket, lhsTypeDesc);
            STTypedBindingPatternNode rhsTypedBindingPattern = (STTypedBindingPatternNode) typedBindingPatternOrExpr;
            STNode newTypeDesc;
            if (pipeOrAndToken.kind == SyntaxKind.PIPE_TOKEN) {
                newTypeDesc = createUnionTypeDesc(lhsTypeDesc, pipeOrAndToken, rhsTypedBindingPattern.typeDescriptor);
            } else {
                newTypeDesc =
                        createIntersectionTypeDesc(lhsTypeDesc, pipeOrAndToken, rhsTypedBindingPattern.typeDescriptor);
            }

            return STNodeFactory.createTypedBindingPatternNode(newTypeDesc, rhsTypedBindingPattern.bindingPattern);
        } else {
            STNode keyExpr = getExpression(member);
            STNode containerExpr = getExpression(typeDescOrExpr);
            STNode lhsExpr =
                    STNodeFactory.createIndexedExpressionNode(containerExpr, openBracket, keyExpr, closeBracket);
            return STNodeFactory.createBinaryExpressionNode(SyntaxKind.BINARY_EXPRESSION, lhsExpr, pipeOrAndToken,
                    typedBindingPatternOrExpr);
        }
    }

    private STNode getArrayTypeDesc(STNode openBracket, STNode member, STNode closeBracket, STNode lhsTypeDesc) {
        if (lhsTypeDesc.kind == SyntaxKind.UNION_TYPE_DESC) {
            STUnionTypeDescriptorNode unionTypeDesc = (STUnionTypeDescriptorNode) lhsTypeDesc;
            STNode middleTypeDesc = getArrayTypeDesc(openBracket, member, closeBracket, unionTypeDesc.rightTypeDesc);
            lhsTypeDesc = createUnionTypeDesc(unionTypeDesc.leftTypeDesc, unionTypeDesc.pipeToken, middleTypeDesc);
        } else if (lhsTypeDesc.kind == SyntaxKind.INTERSECTION_TYPE_DESC) {
            STIntersectionTypeDescriptorNode intersectionTypeDesc = (STIntersectionTypeDescriptorNode) lhsTypeDesc;
            STNode middleTypeDesc =
                    getArrayTypeDesc(openBracket, member, closeBracket, intersectionTypeDesc.rightTypeDesc);
            lhsTypeDesc = createIntersectionTypeDesc(intersectionTypeDesc.leftTypeDesc,
                    intersectionTypeDesc.bitwiseAndToken, middleTypeDesc);
        } else {
            lhsTypeDesc = createArrayTypeDesc(lhsTypeDesc, openBracket, member, closeBracket);
        }
        return lhsTypeDesc;
    }

    /**
     * Parse union (|) or intersection (&) type operator.
     *
     * @return pipe or bitwise and token
     */
    private STNode parseUnionOrIntersectionToken() {
        STToken token = peek();
        if (token.kind == SyntaxKind.PIPE_TOKEN || token.kind == SyntaxKind.BITWISE_AND_TOKEN) {
            return consume();
        } else {
            Solution sol = recover(token, ParserRuleContext.UNION_OR_INTERSECTION_TOKEN);
            return sol.recoveredNode;
        }
    }

    /**
     * Infer the type of the ambiguous bracketed list, based on the type of the member.
     *
     * @param memberNode Member node
     * @return Inferred type of the bracketed list
     */
    private SyntaxKind getBracketedListNodeType(STNode memberNode) {
        if (isEmpty(memberNode)) {
            // empty brackets. could be array-type or list-binding-pattern
            return SyntaxKind.NONE;
        }

        if (isDefiniteTypeDesc(memberNode.kind)) {
            return SyntaxKind.TUPLE_TYPE_DESC;
        }

        switch (memberNode.kind) {
            case ASTERISK_TOKEN:
                return SyntaxKind.ARRAY_TYPE_DESC;
            case CAPTURE_BINDING_PATTERN:
            case LIST_BINDING_PATTERN:
            case REST_BINDING_PATTERN:
            case MAPPING_BINDING_PATTERN:
            case WILDCARD_BINDING_PATTERN:
                return SyntaxKind.LIST_BINDING_PATTERN;
            case QUALIFIED_NAME_REFERENCE: // a qualified-name-ref can only be a type-ref
            case REST_TYPE:
                return SyntaxKind.TUPLE_TYPE_DESC;
            case DECIMAL_INTEGER_LITERAL: // member is a const expression. could be array-type or member-access
            case HEX_INTEGER_LITERAL:
            case SIMPLE_NAME_REFERENCE: // member is a simple type-ref/var-ref
            case BRACKETED_LIST: // member is again ambiguous
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
                return SyntaxKind.NONE;
            default:
                return SyntaxKind.INDEXED_EXPRESSION;
        }
    }

    // --------------------------------- Statements starts with open-bracket ---------------------------------

    /*
     * This section tries to break the ambiguity in parsing a statement that starts with a open-bracket.
     * The ambiguity lies in between:
     * 1) Assignment that starts with list binding pattern
     * 2) Var-decl statement that starts with tuple type
     * 3) Statement that starts with list constructor, such as sync-send, etc.
     */

    /**
     * Parse any statement that starts with an open-bracket.
     *
     * @param annots Annotations attached to the statement.
     * @return Parsed node
     */
    private STNode parseStatementStartsWithOpenBracket(STNode annots, boolean possibleMappingField) {
        startContext(ParserRuleContext.ASSIGNMENT_OR_VAR_DECL_STMT);
        return parseStatementStartsWithOpenBracket(annots, true, possibleMappingField);
    }

    private STNode parseMemberBracketedList(boolean possibleMappingField) {
        STNode annots = STNodeFactory.createEmptyNodeList();
        return parseStatementStartsWithOpenBracket(annots, false, possibleMappingField);
    }

    /**
     * The bracketed list at the start of a statement can be one of the following.
     * 1) List binding pattern
     * 2) Tuple type
     * 3) List constructor
     *
     * @param isRoot Is this the root of the list
     * @return Parsed node
     */
    private STNode parseStatementStartsWithOpenBracket(STNode annots, boolean isRoot, boolean possibleMappingField) {
        startContext(ParserRuleContext.STMT_START_BRACKETED_LIST);
        STNode openBracket = parseOpenBracket();
        List<STNode> memberList = new ArrayList<>();
        while (!isBracketedListEnd(peek().kind)) {
            // Parse member
            STNode member = parseStatementStartBracketedListMember();
            SyntaxKind currentNodeType = getStmtStartBracketedListType(member);

            switch (currentNodeType) {
                case TUPLE_TYPE_DESC:
                    // If the member type was figured out as a tuple-type-desc member, then parse the
                    // remaining members as tuple type members and be done with it.
                    return parseAsTupleTypeDesc(annots, openBracket, memberList, member, isRoot);
                case LIST_BINDING_PATTERN:
                    // If the member type was figured out as a binding pattern, then parse the
                    // remaining members as binding patterns and be done with it.
                    return parseAsListBindingPattern(openBracket, memberList, member, isRoot);
                case LIST_CONSTRUCTOR:
                    // If the member type was figured out as a list constructor, then parse the
                    // remaining members as list constructor members and be done with it.
                    return parseAsListConstructor(openBracket, memberList, member, isRoot);
                case LIST_BP_OR_LIST_CONSTRUCTOR:
                    return parseAsListBindingPatternOrListConstructor(openBracket, memberList, member, isRoot);
                case TUPLE_TYPE_DESC_OR_LIST_CONST:
                    return parseAsTupleTypeDescOrListConstructor(annots, openBracket, memberList, member, isRoot);
                case NONE:
                default:
                    memberList.add(member);
                    break;
            }

            // Parse separator
            STNode memberEnd = parseBracketedListMemberEnd();
            if (memberEnd == null) {
                break;
            }
            memberList.add(memberEnd);
        }

        // We reach here if it is still ambiguous, even after parsing the full list.
        STNode closeBracket = parseCloseBracket();
        STNode bracketedList = parseStatementStartBracketedList(annots, openBracket, memberList, closeBracket, isRoot,
                possibleMappingField);
        return bracketedList;
    }

    private STNode parseStatementStartBracketedListMember() {
        STToken nextToken = peek();
        return parseStatementStartBracketedListMember(nextToken.kind);
    }

    /**
     * Parse a member of a list-binding-pattern, tuple-type-desc, or
     * list-constructor-expr, when the parent is ambiguous.
     *
     * @param nextTokenKind Kind of the next token.
     * @return Parsed node
     */
    private STNode parseStatementStartBracketedListMember(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case OPEN_BRACKET_TOKEN:
                return parseMemberBracketedList(false);
            case IDENTIFIER_TOKEN:
                STNode identifier = parseQualifiedIdentifier(ParserRuleContext.VARIABLE_REF);
                if (isWildcardBP(identifier)) {
                    STNode varName = ((STSimpleNameReferenceNode) identifier).name;
                    return getWildcardBindingPattern(varName);
                }

                nextTokenKind = peek().kind;
                if (nextTokenKind == SyntaxKind.ELLIPSIS_TOKEN) {
                    STNode ellipsis = parseEllipsis();
                    return STNodeFactory.createRestDescriptorNode(identifier, ellipsis);
                }

                // we don't know which one
                // TODO: handle function-binding-pattern
                // TODO handle & and |
                return parseExpressionRhs(DEFAULT_OP_PRECEDENCE, identifier, false, true);
            case OPEN_BRACE_TOKEN:
                // mapping-binding-pattern
                return parseMappingBindingPatterOrMappingConstructor();
            case ERROR_KEYWORD:
                if (getNextNextToken(nextTokenKind).kind == SyntaxKind.OPEN_PAREN_TOKEN) {
                    return parseErrorConstructorExpr();
                }

                // error-type-desc
                return parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TUPLE);
            case ELLIPSIS_TOKEN:
                return parseListBindingPatternMember();
            case XML_KEYWORD:
            case STRING_KEYWORD:
                if (getNextNextToken(nextTokenKind).kind == SyntaxKind.BACKTICK_TOKEN) {
                    return parseExpression(false);
                }
                return parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TUPLE);
            case TABLE_KEYWORD:
            case STREAM_KEYWORD:
                if (getNextNextToken(nextTokenKind).kind == SyntaxKind.LT_TOKEN) {
                    return parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TUPLE);
                }
                return parseExpression(false);
            case OPEN_PAREN_TOKEN:
                return parseTypeDescOrExpr();
            default:
                if (isValidExpressionStart(nextTokenKind, 1)) {
                    return parseExpression(false);
                }

                if (isTypeStartingToken(nextTokenKind)) {
                    return parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TUPLE);
                }

                Solution solution = recover(peek(), ParserRuleContext.STMT_START_BRACKETED_LIST_MEMBER);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseStatementStartBracketedListMember(solution.tokenKind);
        }
    }

    private STNode parseAsTupleTypeDescOrListConstructor(STNode annots, STNode openBracket, List<STNode> memberList,
                                                         STNode member, boolean isRoot) {
        memberList.add(member);
        STNode memberEnd = parseBracketedListMemberEnd();

        STNode tupleTypeDescOrListCons;
        if (memberEnd == null) {
            // We reach here if it is still ambiguous, even after parsing the full list.
            STNode closeBracket = parseCloseBracket();
            tupleTypeDescOrListCons =
                    parseTupleTypeDescOrListConstructorRhs(openBracket, memberList, closeBracket, isRoot);
        } else {
            memberList.add(memberEnd);
            tupleTypeDescOrListCons = parseTupleTypeDescOrListConstructor(annots, openBracket, memberList, isRoot);
        }

        return tupleTypeDescOrListCons;
    }

    /**
     * Parse tuple type desc or list constructor.
     *
     * @return Parsed node
     */
    private STNode parseTupleTypeDescOrListConstructor(STNode annots) {
        startContext(ParserRuleContext.BRACKETED_LIST);
        STNode openBracket = parseOpenBracket();
        List<STNode> memberList = new ArrayList<>();
        return parseTupleTypeDescOrListConstructor(annots, openBracket, memberList, false);
    }

    private STNode parseTupleTypeDescOrListConstructor(STNode annots, STNode openBracket, List<STNode> memberList,
                                                       boolean isRoot) {
        // Parse the members
        STToken nextToken = peek();
        while (!isBracketedListEnd(nextToken.kind)) {
            // Parse member
            STNode member = parseTupleTypeDescOrListConstructorMember(nextToken.kind, annots);
            SyntaxKind currentNodeType = getParsingNodeTypeOfTupleTypeOrListCons(member);

            switch (currentNodeType) {
                case LIST_CONSTRUCTOR:
                    // If the member type was figured out as a list constructor, then parse the
                    // remaining members as list constructor members and be done with it.
                    return parseAsListConstructor(openBracket, memberList, member, isRoot);
                case TUPLE_TYPE_DESC:
                    // If the member type was figured out as a tuple-type-desc member, then parse the
                    // remaining members as tuple type members and be done with it.
                    return parseAsTupleTypeDesc(annots, openBracket, memberList, member, isRoot);
                case TUPLE_TYPE_DESC_OR_LIST_CONST:
                default:
                    memberList.add(member);
                    break;
            }

            // Parse separator
            STNode memberEnd = parseBracketedListMemberEnd();
            if (memberEnd == null) {
                break;
            }
            memberList.add(memberEnd);
            nextToken = peek();
        }

        // We reach here if it is still ambiguous, even after parsing the full list.
        STNode closeBracket = parseCloseBracket();
        return parseTupleTypeDescOrListConstructorRhs(openBracket, memberList, closeBracket, isRoot);
    }

    private STNode parseTupleTypeDescOrListConstructorMember(STNode annots) {
        return parseTupleTypeDescOrListConstructorMember(peek().kind, annots);
    }

    private STNode parseTupleTypeDescOrListConstructorMember(SyntaxKind nextTokenKind, STNode annots) {
        switch (nextTokenKind) {
            case OPEN_BRACKET_TOKEN:
                // we don't know which one
                return parseTupleTypeDescOrListConstructor(annots);
            case IDENTIFIER_TOKEN:
                STNode identifier = parseQualifiedIdentifier(ParserRuleContext.VARIABLE_REF);
                // we don't know which one can be array type desc or expr
                nextTokenKind = peek().kind;
                if (nextTokenKind == SyntaxKind.ELLIPSIS_TOKEN) {
                    STNode ellipsis = parseEllipsis();
                    return STNodeFactory.createRestDescriptorNode(identifier, ellipsis);
                }
                return parseExpressionRhs(DEFAULT_OP_PRECEDENCE, identifier, false, false);
            case OPEN_BRACE_TOKEN:
                // mapping-const
                return parseMappingConstructorExpr();
            case ERROR_KEYWORD:
                if (getNextNextToken(nextTokenKind).kind == SyntaxKind.OPEN_PAREN_TOKEN) {
                    return parseErrorConstructorExpr();
                }

                // error-type-desc
                return parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TUPLE);
            case XML_KEYWORD:
            case STRING_KEYWORD:
                if (getNextNextToken(nextTokenKind).kind == SyntaxKind.BACKTICK_TOKEN) {
                    return parseExpression(false);
                }
                return parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TUPLE);
            case TABLE_KEYWORD:
            case STREAM_KEYWORD:
                if (getNextNextToken(nextTokenKind).kind == SyntaxKind.LT_TOKEN) {
                    return parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TUPLE);
                }
                return parseExpression(false);
            case OPEN_PAREN_TOKEN:
                return parseTypeDescOrExpr();
            default:
                if (isValidExpressionStart(nextTokenKind, 1)) {
                    return parseExpression(false);
                }

                if (isTypeStartingToken(nextTokenKind)) {
                    return parseTypeDescriptor(ParserRuleContext.TYPE_DESC_IN_TUPLE);
                }

                Solution solution = recover(peek(), ParserRuleContext.TUPLE_TYPE_DESC_OR_LIST_CONST_MEMBER, annots);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseTupleTypeDescOrListConstructorMember(solution.tokenKind, annots);
        }
    }

    private SyntaxKind getParsingNodeTypeOfTupleTypeOrListCons(STNode memberNode) {
        // We can use the same method
        return getStmtStartBracketedListType(memberNode);
    }

    private STNode parseTupleTypeDescOrListConstructorRhs(STNode openBracket, List<STNode> members, STNode closeBracket,
                                                          boolean isRoot) {
        STNode tupleTypeOrListConst;
        switch (peek().kind) {
            case COMMA_TOKEN: // [a, b, c],
            case CLOSE_BRACE_TOKEN: // [a, b, c]}
            case CLOSE_BRACKET_TOKEN:// [a, b, c]]
                if (!isRoot) {
                    endContext();
                    return new STAmbiguousCollectionNode(SyntaxKind.TUPLE_TYPE_DESC_OR_LIST_CONST, openBracket, members,
                            closeBracket);
                }
                // fall through
            default:
                if (isValidExprRhsStart(peek().kind, closeBracket.kind) ||
                        (isRoot && peek().kind == SyntaxKind.EQUAL_TOKEN)) {
                    members = getExpressionList(members);
                    STNode memberExpressions = STNodeFactory.createNodeList(members);
                    tupleTypeOrListConst = STNodeFactory.createListConstructorExpressionNode(openBracket,
                            memberExpressions, closeBracket);
                    break;
                }

                // Treat everything else as tuple type desc
                STNode memberTypeDescs = STNodeFactory.createNodeList(getTypeDescList(members));
                STNode tupleTypeDesc =
                        STNodeFactory.createTupleTypeDescriptorNode(openBracket, memberTypeDescs, closeBracket);
                tupleTypeOrListConst =
                        parseComplexTypeDescriptor(tupleTypeDesc, ParserRuleContext.TYPE_DESC_IN_TUPLE, false);
        }

        endContext();

        if (!isRoot) {
            return tupleTypeOrListConst;
        }

        return parseStmtStartsWithTupleTypeOrExprRhs(null, tupleTypeOrListConst, isRoot);

    }

    private STNode parseStmtStartsWithTupleTypeOrExprRhs(STNode annots, STNode tupleTypeOrListConst, boolean isRoot) {
        if (tupleTypeOrListConst.kind.compareTo(SyntaxKind.TYPE_DESC) >= 0 &&
                tupleTypeOrListConst.kind.compareTo(SyntaxKind.TYPEDESC_TYPE_DESC) <= 0) {
            STNode finalKeyword = STNodeFactory.createEmptyNode();
            STNode typedBindingPattern =
                    parseTypedBindingPatternTypeRhs(tupleTypeOrListConst, ParserRuleContext.VAR_DECL_STMT, isRoot);
            if (!isRoot) {
                return typedBindingPattern;
            }
            switchContext(ParserRuleContext.VAR_DECL_STMT);
            return parseVarDeclRhs(annots, finalKeyword, typedBindingPattern, false);
        }

        STNode expr = getExpression(tupleTypeOrListConst);
        expr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, expr, false, true);
        return parseStatementStartWithExprRhs(expr);
    }

    private STNode parseAsTupleTypeDesc(STNode annots, STNode openBracket, List<STNode> memberList, STNode member,
                                        boolean isRoot) {
        memberList = getTypeDescList(memberList);
        startContext(ParserRuleContext.TYPE_DESC_IN_TUPLE);
        STNode tupleTypeMembers = parseTupleTypeMembers(member, memberList);
        STNode closeBracket = parseCloseBracket();
        endContext();

        STNode tupleType = STNodeFactory.createTupleTypeDescriptorNode(openBracket, tupleTypeMembers, closeBracket);
        STNode typeDesc =
                parseComplexTypeDescriptor(tupleType, ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN, true);
        endContext();
        STNode typedBindingPattern = parseTypedBindingPatternTypeRhs(typeDesc, ParserRuleContext.VAR_DECL_STMT, isRoot);
        if (!isRoot) {
            return typedBindingPattern;
        }

        switchContext(ParserRuleContext.VAR_DECL_STMT);
        return parseVarDeclRhs(annots, STNodeFactory.createEmptyNode(), typedBindingPattern, false);
    }

    private STNode parseAsListBindingPattern(STNode openBracket, List<STNode> memberList, STNode member,
                                             boolean isRoot) {
        memberList = getBindingPatternsList(memberList);
        memberList.add(member);
        switchContext(ParserRuleContext.LIST_BINDING_PATTERN);
        STNode listBindingPattern = parseListBindingPattern(openBracket, member, memberList);
        endContext();
        if (!isRoot) {
            return listBindingPattern;
        }

        return parseAssignmentStmtRhs(listBindingPattern);
    }

    private STNode parseAsListBindingPattern(STNode openBracket, List<STNode> memberList) {
        memberList = getBindingPatternsList(memberList);
        switchContext(ParserRuleContext.LIST_BINDING_PATTERN);
        STNode listBindingPattern = parseListBindingPattern(openBracket, memberList);
        endContext();
        return listBindingPattern;
    }

    private STNode parseAsListBindingPatternOrListConstructor(STNode openBracket, List<STNode> memberList,
                                                              STNode member, boolean isRoot) {
        memberList.add(member);
        STNode memberEnd = parseBracketedListMemberEnd();

        STNode listBindingPatternOrListCons;
        if (memberEnd == null) {
            // We reach here if it is still ambiguous, even after parsing the full list.
            STNode closeBracket = parseCloseBracket();
            listBindingPatternOrListCons =
                    parseListBindingPatternOrListConstructor(openBracket, memberList, closeBracket, isRoot);
        } else {
            memberList.add(memberEnd);
            listBindingPatternOrListCons = parseListBindingPatternOrListConstructor(openBracket, memberList, isRoot);
        }

        return listBindingPatternOrListCons;
    }

    private SyntaxKind getStmtStartBracketedListType(STNode memberNode) {
        if (memberNode.kind.compareTo(SyntaxKind.TYPE_DESC) >= 0 &&
                memberNode.kind.compareTo(SyntaxKind.TYPEDESC_TYPE_DESC) <= 0) {
            return SyntaxKind.TUPLE_TYPE_DESC;
        }

        switch (memberNode.kind) {
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case ASTERISK_TOKEN:
                return SyntaxKind.ARRAY_TYPE_DESC;
            case CAPTURE_BINDING_PATTERN:
            case LIST_BINDING_PATTERN:
            case REST_BINDING_PATTERN:
            case WILDCARD_BINDING_PATTERN:
                return SyntaxKind.LIST_BINDING_PATTERN;
            case QUALIFIED_NAME_REFERENCE: // a qualified-name-ref can only be a type-ref
            case REST_TYPE:
                return SyntaxKind.TUPLE_TYPE_DESC;
            case LIST_CONSTRUCTOR:
            case MAPPING_CONSTRUCTOR:
                return SyntaxKind.LIST_CONSTRUCTOR;
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
                // can be either list-bp or list-constructor. Cannot be a tuple-type-desc
                return SyntaxKind.LIST_BP_OR_LIST_CONSTRUCTOR;
            case SIMPLE_NAME_REFERENCE: // member is a simple type-ref/var-ref
            case BRACKETED_LIST: // member is again ambiguous
                return SyntaxKind.NONE;
            case FUNCTION_CALL:
                if (isPosibleFunctionalBindingPattern((STFunctionCallExpressionNode) memberNode)) {
                    return SyntaxKind.NONE;
                }
                return SyntaxKind.LIST_CONSTRUCTOR;
            case INDEXED_EXPRESSION:
                return SyntaxKind.TUPLE_TYPE_DESC_OR_LIST_CONST;
            default:
                if (isExpression(memberNode.kind) && !isAllBasicLiterals(memberNode) && !isAmbiguous(memberNode)) {
                    return SyntaxKind.LIST_CONSTRUCTOR;
                }

                // can be anyof the three.
                return SyntaxKind.NONE;
        }
    }

    private boolean isPosibleFunctionalBindingPattern(STFunctionCallExpressionNode funcCall) {
        STNode args = funcCall.arguments;
        int size = args.bucketCount();

        for (int i = 0; i < size; i++) {
            STNode arg = args.childInBucket(i);
            if (arg.kind != SyntaxKind.NAMED_ARG && arg.kind != SyntaxKind.POSITIONAL_ARG &&
                    arg.kind != SyntaxKind.REST_ARG) {
                continue;
            }

            if (!isPosibleArgBindingPattern((STFunctionArgumentNode) arg)) {
                return false;
            }
        }

        return true;
    }

    private boolean isPosibleArgBindingPattern(STFunctionArgumentNode arg) {
        switch (arg.kind) {
            case POSITIONAL_ARG:
                STNode expr = ((STPositionalArgumentNode) arg).expression;
                return isPosibleBindingPattern(expr);
            case NAMED_ARG:
                expr = ((STNamedArgumentNode) arg).expression;
                return isPosibleBindingPattern(expr);
            case REST_ARG:
                expr = ((STRestArgumentNode) arg).expression;
                return expr.kind == SyntaxKind.SIMPLE_NAME_REFERENCE;
            default:
                return false;
        }
    }

    private boolean isPosibleBindingPattern(STNode node) {
        switch (node.kind) {
            case SIMPLE_NAME_REFERENCE:
                return true;
            case LIST_CONSTRUCTOR:
                STListConstructorExpressionNode listConstructor = (STListConstructorExpressionNode) node;
                for (int i = 0; i < listConstructor.bucketCount(); i++) {
                    STNode expr = listConstructor.childInBucket(i);
                    if (!isPosibleBindingPattern(expr)) {
                        return false;
                    }
                }
                return true;
            case MAPPING_CONSTRUCTOR:
                STMappingConstructorExpressionNode mappingConstructor = (STMappingConstructorExpressionNode) node;
                for (int i = 0; i < mappingConstructor.bucketCount(); i++) {
                    STNode expr = mappingConstructor.childInBucket(i);
                    if (!isPosibleBindingPattern(expr)) {
                        return false;
                    }
                }
                return true;
            case SPECIFIC_FIELD:
                STSpecificFieldNode specificField = (STSpecificFieldNode) node;
                if (specificField.readonlyKeyword != null) {
                    return false;
                }

                if (specificField.valueExpr == null) {
                    return true;
                }

                return isPosibleBindingPattern(specificField.valueExpr);
            case FUNCTION_CALL:
                return isPosibleFunctionalBindingPattern((STFunctionCallExpressionNode) node);
            default:
                return false;
        }
    }

    private STNode parseStatementStartBracketedList(STNode annots, STNode openBracket, List<STNode> members,
                                                    STNode closeBracket, boolean isRoot, boolean possibleMappingField) {
        STToken nextToken = peek();
        switch (nextToken.kind) {
            case EQUAL_TOKEN:
                if (!isRoot) {
                    endContext();
                    return new STAmbiguousCollectionNode(SyntaxKind.BRACKETED_LIST, openBracket, members, closeBracket);
                }

                STNode memberBindingPatterns = STNodeFactory.createNodeList(getBindingPatternsList(members));
                STNode restBindingPattern = STNodeFactory.createEmptyNode();
                STNode listBindingPattern = STNodeFactory.createListBindingPatternNode(openBracket,
                        memberBindingPatterns, restBindingPattern, closeBracket);
                endContext(); // end tuple typ-desc

                switchContext(ParserRuleContext.ASSIGNMENT_STMT);
                return parseAssignmentStmtRhs(listBindingPattern);
            case IDENTIFIER_TOKEN:
            case OPEN_BRACE_TOKEN:
                if (!isRoot) {
                    endContext();
                    return new STAmbiguousCollectionNode(SyntaxKind.BRACKETED_LIST, openBracket, members, closeBracket);
                }

                if (members.isEmpty()) {
                    openBracket =
                            SyntaxErrors.addDiagnostic(openBracket, DiagnosticErrorCode.ERROR_MISSING_TUPLE_MEMBER);
                }

                switchContext(ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN);
                startContext(ParserRuleContext.TYPE_DESC_IN_TUPLE);
                STNode memberTypeDescs = STNodeFactory.createNodeList(getTypeDescList(members));
                STNode tupleTypeDesc =
                        STNodeFactory.createTupleTypeDescriptorNode(openBracket, memberTypeDescs, closeBracket);
                endContext(); // end tuple typ-desc
                STNode typeDesc = parseComplexTypeDescriptor(tupleTypeDesc,
                        ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN, true);
                STNode typedBindingPattern = parseTypedBindingPatternTypeRhs(typeDesc, ParserRuleContext.VAR_DECL_STMT);
                endContext(); // end binding pattern
                return parseStmtStartsWithTypedBPOrExprRhs(annots, typedBindingPattern);
            case OPEN_BRACKET_TOKEN:
                // [a, ..][..
                // definitely not binding pattern. Can be type-desc or list-constructor
                if (!isRoot) {
                    // if this is a member, treat as type-desc.
                    // TODO: handle expression case.
                    memberTypeDescs = STNodeFactory.createNodeList(getTypeDescList(members));
                    tupleTypeDesc =
                            STNodeFactory.createTupleTypeDescriptorNode(openBracket, memberTypeDescs, closeBracket);
                    endContext();
                    typeDesc = parseComplexTypeDescriptor(tupleTypeDesc, ParserRuleContext.TYPE_DESC_IN_TUPLE, false);
                    return typeDesc;
                }

                STAmbiguousCollectionNode list =
                        new STAmbiguousCollectionNode(SyntaxKind.BRACKETED_LIST, openBracket, members, closeBracket);
                endContext();
                STNode tpbOrExpr = parseTypedBindingPatternOrExprRhs(list, true);
                return parseStmtStartsWithTypedBPOrExprRhs(annots, tpbOrExpr);
            case COLON_TOKEN: // "{[a]:" could be a computed-name-field in mapping-constructor
                if (possibleMappingField && members.size() == 1) {
                    startContext(ParserRuleContext.MAPPING_CONSTRUCTOR);
                    STNode colon = parseColon();
                    STNode fieldNameExpr = getExpression(members.get(0));
                    STNode valueExpr = parseExpression();
                    return STNodeFactory.createComputedNameFieldNode(openBracket, fieldNameExpr, closeBracket, colon,
                            valueExpr);
                }

                // fall through
            default:
                endContext();
                if (!isRoot) {
                    return new STAmbiguousCollectionNode(SyntaxKind.BRACKETED_LIST, openBracket, members, closeBracket);
                }

                list = new STAmbiguousCollectionNode(SyntaxKind.BRACKETED_LIST, openBracket, members, closeBracket);
                STNode exprOrTPB = parseTypedBindingPatternOrExprRhs(list, false);
                return parseStmtStartsWithTypedBPOrExprRhs(annots, exprOrTPB);
        }
    }

    private boolean isWildcardBP(STNode node) {
        switch (node.kind) {
            case SIMPLE_NAME_REFERENCE:
                STToken nameToken = (STToken) ((STSimpleNameReferenceNode) node).name;
                return isUnderscoreToken(nameToken);
            case IDENTIFIER_TOKEN:
                return isUnderscoreToken((STToken) node);
            default:
                return false;

        }
    }

    private boolean isUnderscoreToken(STToken token) {
        return "_".equals(token.text());
    }

    private STNode getWildcardBindingPattern(STNode identifier) {
        switch (identifier.kind) {
            case SIMPLE_NAME_REFERENCE:
                STNode varName = ((STSimpleNameReferenceNode) identifier).name;
                return STNodeFactory.createWildcardBindingPatternNode(varName);
            case IDENTIFIER_TOKEN:
                return STNodeFactory.createWildcardBindingPatternNode(identifier);
            default:
                throw new IllegalStateException();
        }
    }

    // --------------------------------- Statements starts with open-brace ---------------------------------

    /*
     * This section tries to break the ambiguity in parsing a statement that starts with a open-brace.
     */

    /**
     * Parse statements that starts with open-brace. It could be a:
     * 1) Block statement
     * 2) Var-decl with mapping binding pattern.
     * 3) Statement that starts with mapping constructor expression.
     *
     * @return Parsed node
     */
    private STNode parseStatementStartsWithOpenBrace() {
        startContext(ParserRuleContext.AMBIGUOUS_STMT);
        STNode openBrace = parseOpenBrace();
        if (peek().kind == SyntaxKind.CLOSE_BRACE_TOKEN) {
            STNode closeBrace = parseCloseBrace();
            switch (peek().kind) {
                case EQUAL_TOKEN:
                    switchContext(ParserRuleContext.ASSIGNMENT_STMT);
                    STNode fields = STNodeFactory.createEmptyNodeList();
                    STNode restBindingPattern = STNodeFactory.createEmptyNode();
                    STNode bindingPattern = STNodeFactory.createMappingBindingPatternNode(openBrace, fields,
                            restBindingPattern, closeBrace);
                    return parseAssignmentStmtRhs(bindingPattern);
                case RIGHT_ARROW_TOKEN:
                case SYNC_SEND_TOKEN:
                    switchContext(ParserRuleContext.EXPRESSION_STATEMENT);
                    fields = STNodeFactory.createEmptyNodeList();
                    STNode expr = STNodeFactory.createMappingConstructorExpressionNode(openBrace, fields, closeBrace);
                    expr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, expr, false, true);
                    return parseStatementStartWithExprRhs(expr);
                default:
                    // else treat as block statement.
                    STNode statements = STNodeFactory.createEmptyNodeList();
                    endContext();
                    return STNodeFactory.createBlockStatementNode(openBrace, statements, closeBrace);
            }
        }

        STNode member = parseStatementStartingBracedListFirstMember();
        SyntaxKind nodeType = getBracedListType(member);
        STNode stmt;
        switch (nodeType) {
            case MAPPING_BINDING_PATTERN:
                return parseStmtAsMappingBindingPatternStart(openBrace, member);
            case MAPPING_CONSTRUCTOR:
                return parseStmtAsMappingConstructorStart(openBrace, member);
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
                return parseStmtAsMappingBPOrMappingConsStart(openBrace, member);
            case BLOCK_STATEMENT:
                STNode closeBrace = parseCloseBrace();
                stmt = STNodeFactory.createBlockStatementNode(openBrace, member, closeBrace);
                endContext();
                return stmt;
            default:
                // any statement comes here
                ArrayList<STNode> stmts = new ArrayList<>();
                stmts.add(member);
                STNode statements = parseStatements(stmts);
                closeBrace = parseCloseBrace();
                endContext();
                return STNodeFactory.createBlockStatementNode(openBrace, statements, closeBrace);
        }
    }

    /**
     * Parse the rest of the statement, treating the start as a mapping binding pattern.
     *
     * @param openBrace Open brace
     * @param firstMappingField First member
     * @return Parsed node
     */
    private STNode parseStmtAsMappingBindingPatternStart(STNode openBrace, STNode firstMappingField) {
        switchContext(ParserRuleContext.ASSIGNMENT_STMT);
        startContext(ParserRuleContext.MAPPING_BINDING_PATTERN);
        List<STNode> bindingPatterns = new ArrayList<>();
        if (firstMappingField.kind != SyntaxKind.REST_BINDING_PATTERN) {
            bindingPatterns.add(getBindingPattern(firstMappingField));
        }

        STNode mappingBP = parseMappingBindingPattern(openBrace, bindingPatterns, firstMappingField);
        return parseAssignmentStmtRhs(mappingBP);
    }

    /**
     * Parse the rest of the statement, treating the start as a mapping constructor expression.
     *
     * @param openBrace Open brace
     * @param firstMember First member
     * @return Parsed node
     */
    private STNode parseStmtAsMappingConstructorStart(STNode openBrace, STNode firstMember) {
        switchContext(ParserRuleContext.EXPRESSION_STATEMENT);
        startContext(ParserRuleContext.MAPPING_CONSTRUCTOR);
        List<STNode> members = new ArrayList<>();
        STNode mappingCons = parseAsMappingConstructor(openBrace, members, firstMember);

        // Create the statement
        STNode expr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, mappingCons, false, true);
        return parseStatementStartWithExprRhs(expr);
    }

    /**
     * Parse the braced-list as a mapping constructor expression.
     *
     * @param openBrace Open brace
     * @param members members list
     * @param member Most recently parsed member
     * @return Parsed node
     */
    private STNode parseAsMappingConstructor(STNode openBrace, List<STNode> members, STNode member) {
        members.add(member);
        members = getExpressionList(members);

        // create mapping constructor
        switchContext(ParserRuleContext.MAPPING_CONSTRUCTOR);
        STNode fields = parseMappingConstructorFields(members);
        STNode closeBrace = parseCloseBrace();
        endContext();
        return STNodeFactory.createMappingConstructorExpressionNode(openBrace, fields, closeBrace);
    }

    /**
     * Parse the rest of the statement, treating the start as a mapping binding pattern
     * or a mapping constructor expression.
     *
     * @param openBrace Open brace
     * @param member First member
     * @return Parsed node
     */
    private STNode parseStmtAsMappingBPOrMappingConsStart(STNode openBrace, STNode member) {
        startContext(ParserRuleContext.MAPPING_BP_OR_MAPPING_CONSTRUCTOR);
        List<STNode> members = new ArrayList<>();
        members.add(member);

        STNode bpOrConstructor;
        STNode memberEnd = parseMappingFieldEnd();
        if (memberEnd == null) {
            STNode closeBrace = parseCloseBrace();
            // We reach here if it is still ambiguous, even after parsing the full list.
            bpOrConstructor = parseMappingBindingPatternOrMappingConstructor(openBrace, members, closeBrace);
        } else {
            members.add(memberEnd);
            bpOrConstructor = parseMappingBindingPatternOrMappingConstructor(openBrace, members);;
        }

        switch (bpOrConstructor.kind) {
            case MAPPING_CONSTRUCTOR:
                // Create the statement
                switchContext(ParserRuleContext.EXPRESSION_STATEMENT);
                STNode expr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, bpOrConstructor, false, true);
                return parseStatementStartWithExprRhs(expr);
            case MAPPING_BINDING_PATTERN:
                switchContext(ParserRuleContext.ASSIGNMENT_STMT);
                STNode bindingPattern = getBindingPattern(bpOrConstructor);
                return parseAssignmentStmtRhs(bindingPattern);
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
            default:
                // If this is followed by an assignment, then treat this node as mapping-binding pattern.
                if (peek().kind == SyntaxKind.EQUAL_TOKEN) {
                    switchContext(ParserRuleContext.ASSIGNMENT_STMT);
                    bindingPattern = getBindingPattern(bpOrConstructor);
                    return parseAssignmentStmtRhs(bindingPattern);
                }

                // else treat as expression.
                switchContext(ParserRuleContext.EXPRESSION_STATEMENT);
                expr = getExpression(bpOrConstructor);
                expr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, expr, false, true);
                return parseStatementStartWithExprRhs(expr);
        }
    }

    /**
     * Parse a member of a braced-list that occurs at the start of a statement.
     *
     * @return Parsed node
     */
    private STNode parseStatementStartingBracedListFirstMember() {
        STToken nextToken = peek();
        switch (nextToken.kind) {
            case READONLY_KEYWORD:
                STNode readonlyKeyword = parseReadonlyKeyword();
                return bracedListMemberStartsWithReadonly(readonlyKeyword);
            case IDENTIFIER_TOKEN:
                readonlyKeyword = STNodeFactory.createEmptyNode();
                return parseIdentifierRhsInStmtStartingBrace(readonlyKeyword);
            case STRING_LITERAL:
                STNode key = parseStringLiteral();
                if (peek().kind == SyntaxKind.COLON_TOKEN) {
                    readonlyKeyword = STNodeFactory.createEmptyNode();
                    STNode colon = parseColon();
                    STNode valueExpr = parseExpression();
                    return STNodeFactory.createSpecificFieldNode(readonlyKeyword, key, colon, valueExpr);
                }
                key = STNodeFactory.createBasicLiteralNode(key.kind, key);
                switchContext(ParserRuleContext.BLOCK_STMT);
                startContext(ParserRuleContext.AMBIGUOUS_STMT);
                STNode expr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, key, false, true);
                return parseStatementStartWithExprRhs(expr);
            case OPEN_BRACKET_TOKEN:
                // [a] can be tuple type, list-bp, list-constructor or computed-field
                STNode annots = STNodeFactory.createEmptyNodeList();
                return parseStatementStartsWithOpenBracket(annots, true);
            case OPEN_BRACE_TOKEN:
                // Then treat parent as a block statement
                switchContext(ParserRuleContext.BLOCK_STMT);
                return parseStatementStartsWithOpenBrace();
            case ELLIPSIS_TOKEN:
                return parseRestBindingPattern();
            default:
                // Then treat parent as a block statement
                switchContext(ParserRuleContext.BLOCK_STMT);
                return parseStatements();
        }
    }

    private STNode bracedListMemberStartsWithReadonly(STNode readonlyKeyword) {
        STToken nextToken = peek();
        switch (nextToken.kind) {
            case IDENTIFIER_TOKEN:
                return parseIdentifierRhsInStmtStartingBrace(readonlyKeyword);
            case STRING_LITERAL:
                if (peek(2).kind == SyntaxKind.COLON_TOKEN) {
                    STNode key = parseStringLiteral();
                    STNode colon = parseColon();
                    STNode valueExpr = parseExpression();
                    return STNodeFactory.createSpecificFieldNode(readonlyKeyword, key, colon, valueExpr);
                }
                // fall through
            default:
                // Then treat parent as a var-decl statement
                switchContext(ParserRuleContext.BLOCK_STMT);
                startContext(ParserRuleContext.VAR_DECL_STMT);
                startContext(ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN);
                STNode typeDesc = parseComplexTypeDescriptor(readonlyKeyword,
                        ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN, true);
                endContext();
                STNode metadata = STNodeFactory.createEmptyNode();
                STNode finalKeyword = STNodeFactory.createEmptyNode();
                STNode typedBP = parseTypedBindingPatternTypeRhs(typeDesc, ParserRuleContext.VAR_DECL_STMT);
                return parseVarDeclRhs(metadata, finalKeyword, typedBP, false);
        }
    }

    /**
     * Parse the rhs components of an identifier that follows an open brace,
     * at the start of a statement. i.e: "{foo".
     *
     * @param readonlyKeyword Readonly keyword
     * @return Parsed node
     */
    private STNode parseIdentifierRhsInStmtStartingBrace(STNode readonlyKeyword) {
        STNode identifier = parseIdentifier(ParserRuleContext.VARIABLE_REF);
        switch (peek().kind) {
            case COMMA_TOKEN: // { foo,
                // could be map literal or mapping-binding-pattern
                STNode colon = STNodeFactory.createEmptyNode();
                STNode value = STNodeFactory.createEmptyNode();
                return STNodeFactory.createSpecificFieldNode(readonlyKeyword, identifier, colon, value);
            case COLON_TOKEN:
                colon = parseColon();
                if (!isEmpty(readonlyKeyword)) {
                    value = parseExpression();
                    return STNodeFactory.createSpecificFieldNode(readonlyKeyword, identifier, colon, value);
                }

                SyntaxKind nextTokenKind = peek().kind;
                switch (nextTokenKind) {
                    case OPEN_BRACKET_TOKEN: // { foo:[
                        STNode bindingPatternOrExpr = parseListBindingPatternOrListConstructor();
                        return getMappingField(identifier, colon, bindingPatternOrExpr);
                    case OPEN_BRACE_TOKEN: // { foo:{
                        bindingPatternOrExpr = parseMappingBindingPatterOrMappingConstructor();
                        return getMappingField(identifier, colon, bindingPatternOrExpr);
                    case IDENTIFIER_TOKEN: // { foo:bar
                        return parseQualifiedIdentifierRhsInStmtStartBrace(identifier, colon);
                    default:
                        STNode expr = parseExpression();
                        return getMappingField(identifier, colon, expr);
                }
            default:
                switchContext(ParserRuleContext.BLOCK_STMT);
                if (!isEmpty(readonlyKeyword)) {
                    startContext(ParserRuleContext.VAR_DECL_STMT);
                    STNode bindingPattern = STNodeFactory.createCaptureBindingPatternNode(identifier);
                    STNode typedBindingPattern =
                            STNodeFactory.createTypedBindingPatternNode(readonlyKeyword, bindingPattern);
                    STNode metadata = STNodeFactory.createEmptyNode();
                    STNode finalKeyword = STNodeFactory.createEmptyNode();
                    return parseVarDeclRhs(metadata, finalKeyword, typedBindingPattern, false);
                }

                startContext(ParserRuleContext.AMBIGUOUS_STMT);
                STNode qualifiedIdentifier = parseQualifiedIdentifier(identifier, false);
                STNode expr = parseTypedBindingPatternOrExprRhs(qualifiedIdentifier, true);
                STNode annots = STNodeFactory.createEmptyNodeList();
                return parseStmtStartsWithTypedBPOrExprRhs(annots, expr);
        }
    }

    /**
     * Parse the rhs components of "<code>{ identifier : identifier</code>",
     * at the start of a statement. i.e: "{foo:bar".
     *
     * @return Parsed node
     */
    private STNode parseQualifiedIdentifierRhsInStmtStartBrace(STNode identifier, STNode colon) {
        STNode secondIdentifier = parseIdentifier(ParserRuleContext.VARIABLE_REF);
        STNode secondNameRef = STNodeFactory.createSimpleNameReferenceNode(secondIdentifier);
        if (isWildcardBP(secondIdentifier)) {
            // { foo:_
            return getWildcardBindingPattern(secondIdentifier);
        }

        // Reach here for something like: "{foo:bar". This could be anything.
        SyntaxKind nextTokenKind = peek().kind;
        STNode qualifiedNameRef = STNodeFactory.createQualifiedNameReferenceNode(identifier, colon, secondNameRef);
        switch (nextTokenKind) {
            case COMMA_TOKEN:
                // {foo:bar, --> map-literal or binding pattern
                // Return a qualified-name-reference node since this is ambiguous. Downstream code
                // will convert this to the respective node, once the ambiguity is resolved.
                return qualifiedNameRef;
            case OPEN_BRACE_TOKEN: // { foo:bar{ --> var-decl with TBP
            case IDENTIFIER_TOKEN: // var-decl
                STNode finalKeyword = STNodeFactory.createEmptyNode();
                STNode typeBindingPattern =
                        parseTypedBindingPatternTypeRhs(qualifiedNameRef, ParserRuleContext.VAR_DECL_STMT);
                STNode annots = STNodeFactory.createEmptyNodeList();
                return parseVarDeclRhs(annots, finalKeyword, typeBindingPattern, false);
            case OPEN_BRACKET_TOKEN:
                // "{ foo:bar[" Can be (TBP) or (map-literal with member-access) or (statement starts with
                // member-access)
                return parseMemberRhsInStmtStartWithBrace(identifier, colon, secondNameRef);
            case QUESTION_MARK_TOKEN:
                // var-decl
                STNode typeDesc = parseComplexTypeDescriptor(qualifiedNameRef,
                        ParserRuleContext.TYPE_DESC_IN_TYPE_BINDING_PATTERN, true);
                finalKeyword = STNodeFactory.createEmptyNode();
                typeBindingPattern = parseTypedBindingPatternTypeRhs(typeDesc, ParserRuleContext.VAR_DECL_STMT);
                annots = STNodeFactory.createEmptyNodeList();
                return parseVarDeclRhs(annots, finalKeyword, typeBindingPattern, false);
            case EQUAL_TOKEN:
            case SEMICOLON_TOKEN:
                // stmt start with expr
                return parseStatementStartWithExprRhs(qualifiedNameRef);
            case PIPE_TOKEN:
            case BITWISE_AND_TOKEN:
            default:
                return parseMemberWithExprInRhs(identifier, colon, secondNameRef, secondNameRef);
        }
    }

    private SyntaxKind getBracedListType(STNode member) {
        switch (member.kind) {
            case FIELD_BINDING_PATTERN:
            case CAPTURE_BINDING_PATTERN:
            case LIST_BINDING_PATTERN:
            case MAPPING_BINDING_PATTERN:
            case WILDCARD_BINDING_PATTERN:
                return SyntaxKind.MAPPING_BINDING_PATTERN;
            case SPECIFIC_FIELD:
                STNode expr = ((STSpecificFieldNode) member).valueExpr;
                if (expr == null) {
                    return SyntaxKind.MAPPING_BP_OR_MAPPING_CONSTRUCTOR;
                }

                // "{foo," and "{foo:bar," is ambiguous
                switch (expr.kind) {
                    case SIMPLE_NAME_REFERENCE:
                    case LIST_BP_OR_LIST_CONSTRUCTOR:
                    case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
                        return SyntaxKind.MAPPING_BP_OR_MAPPING_CONSTRUCTOR;
                    case FUNCTION_CALL:
                        if (isPosibleFunctionalBindingPattern((STFunctionCallExpressionNode) expr)) {
                            return SyntaxKind.MAPPING_BP_OR_MAPPING_CONSTRUCTOR;
                        }
                        return SyntaxKind.MAPPING_CONSTRUCTOR;
                    default:
                        return SyntaxKind.MAPPING_CONSTRUCTOR;
                }
            case SPREAD_FIELD:
            case COMPUTED_NAME_FIELD:
                return SyntaxKind.MAPPING_CONSTRUCTOR;
            case SIMPLE_NAME_REFERENCE:
            case QUALIFIED_NAME_REFERENCE:
            case LIST_BP_OR_LIST_CONSTRUCTOR:
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
            case REST_BINDING_PATTERN:// ambiguous with spread-field in mapping-constructor
                return SyntaxKind.MAPPING_BP_OR_MAPPING_CONSTRUCTOR;
            case LIST:
                return SyntaxKind.BLOCK_STATEMENT;
            default:
                return SyntaxKind.NONE;
        }
    }

    /**
     * Parse mapping binding pattern or mapping constructor.
     *
     * @return Parsed node
     */
    private STNode parseMappingBindingPatterOrMappingConstructor() {
        startContext(ParserRuleContext.MAPPING_BP_OR_MAPPING_CONSTRUCTOR);
        STNode openBrace = parseOpenBrace();
        List<STNode> memberList = new ArrayList<>();
        return parseMappingBindingPatternOrMappingConstructor(openBrace, memberList);
    }

    private boolean isBracedListEnd(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case EOF_TOKEN:
            case CLOSE_BRACE_TOKEN:
                return true;
            default:
                return false;
        }
    }

    private STNode parseMappingBindingPatternOrMappingConstructor(STNode openBrace, List<STNode> memberList) {
        STToken nextToken = peek();
        while (!isBracedListEnd(nextToken.kind)) {
            // Parse member
            STNode member = parseMappingBindingPatterOrMappingConstructorMember(nextToken.kind);
            SyntaxKind currentNodeType = getTypeOfMappingBPOrMappingCons(member);

            switch (currentNodeType) {
                case MAPPING_CONSTRUCTOR:
                    // If the member type was figured out as a list constructor, then parse the
                    // remaining members as list constructor members and be done with it.
                    return parseAsMappingConstructor(openBrace, memberList, member);
                case MAPPING_BINDING_PATTERN:
                    // If the member type was figured out as a binding pattern, then parse the
                    // remaining members as binding patterns and be done with it.
                    return parseAsMappingBindingPattern(openBrace, memberList, member);
                case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
                default:
                    memberList.add(member);
                    break;
            }

            // Parse separator
            STNode memberEnd = parseMappingFieldEnd();
            if (memberEnd == null) {
                break;
            }
            memberList.add(memberEnd);
            nextToken = peek();
        }

        // We reach here if it is still ambiguous, even after parsing the full list.
        STNode closeBrace = parseCloseBrace();
        return parseMappingBindingPatternOrMappingConstructor(openBrace, memberList, closeBrace);
    }

    private STNode parseMappingBindingPatterOrMappingConstructorMember(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case IDENTIFIER_TOKEN:
                STNode key = parseIdentifier(ParserRuleContext.MAPPING_FIELD_NAME);
                return parseMappingFieldRhs(key);
            case STRING_LITERAL:
                STNode readonlyKeyword = STNodeFactory.createEmptyNode();
                key = parseStringLiteral();
                STNode colon = parseColon();
                STNode valueExpr = parseExpression();
                return STNodeFactory.createSpecificFieldNode(readonlyKeyword, key, colon, valueExpr);
            case OPEN_BRACKET_TOKEN:
                return parseComputedField();
            case ELLIPSIS_TOKEN:
                STNode ellipsis = parseEllipsis();
                STNode expr = parseExpression();
                if (expr.kind == SyntaxKind.SIMPLE_NAME_REFERENCE) {
                    return STNodeFactory.createRestBindingPatternNode(ellipsis, expr);
                }
                return STNodeFactory.createSpreadFieldNode(ellipsis, expr);
            default:
                Solution solution = recover(peek(), ParserRuleContext.MAPPING_BP_OR_MAPPING_CONSTRUCTOR_MEMBER);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseListBindingPatternOrListConstructorMember(solution.tokenKind);
        }
    }

    private STNode parseMappingFieldRhs(STNode key) {
        STToken nextToken = peek();
        return parseMappingFieldRhs(nextToken.kind, key);
    }

    private STNode parseMappingFieldRhs(SyntaxKind tokenKind, STNode key) {
        STNode colon;
        STNode valueExpr;
        switch (tokenKind) {
            case COLON_TOKEN:
                colon = parseColon();
                return parseMappingFieldValue(key, colon);
            case COMMA_TOKEN:
            case CLOSE_BRACE_TOKEN:
                STNode readonlyKeyword = STNodeFactory.createEmptyNode();
                colon = STNodeFactory.createEmptyNode();
                valueExpr = STNodeFactory.createEmptyNode();
                return STNodeFactory.createSpecificFieldNode(readonlyKeyword, key, colon, valueExpr);
            default:
                STToken token = peek();
                Solution solution = recover(token, ParserRuleContext.SPECIFIC_FIELD_RHS, key);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                readonlyKeyword = STNodeFactory.createEmptyNode();
                return parseSpecificFieldRhs(solution.tokenKind, readonlyKeyword, key);
        }
    }

    private STNode parseMappingFieldValue(STNode key, STNode colon) {
        // {foo: ...
        STNode expr;
        switch (peek().kind) {
            case IDENTIFIER_TOKEN:
                expr = parseExpression();
                break;
            case OPEN_BRACKET_TOKEN: // { foo:[
                expr = parseListBindingPatternOrListConstructor();
                break;
            case OPEN_BRACE_TOKEN: // { foo:{
                expr = parseMappingBindingPatterOrMappingConstructor();
                break;
            default:
                expr = parseExpression();
                break;
        }

        if (isBindingPattern(expr.kind)) {
            return STNodeFactory.createFieldBindingPatternFullNode(key, colon, expr);
        }

        STNode readonlyKeyword = STNodeFactory.createEmptyNode();
        return STNodeFactory.createSpecificFieldNode(readonlyKeyword, key, colon, expr);
    }

    private boolean isBindingPattern(SyntaxKind kind) {
        switch (kind) {
            case FIELD_BINDING_PATTERN:
            case MAPPING_BINDING_PATTERN:
            case CAPTURE_BINDING_PATTERN:
            case LIST_BINDING_PATTERN:
            case WILDCARD_BINDING_PATTERN:
                return true;
            default:
                return false;
        }
    }

    private SyntaxKind getTypeOfMappingBPOrMappingCons(STNode memberNode) {
        switch (memberNode.kind) {
            case FIELD_BINDING_PATTERN:
            case MAPPING_BINDING_PATTERN:
            case CAPTURE_BINDING_PATTERN:
            case LIST_BINDING_PATTERN:
            case WILDCARD_BINDING_PATTERN:
                return SyntaxKind.MAPPING_BINDING_PATTERN;
            case SPECIFIC_FIELD:
                STNode expr = ((STSpecificFieldNode) memberNode).valueExpr;
                // "{foo," and "{foo:bar," is ambiguous
                if (expr == null || expr.kind == SyntaxKind.SIMPLE_NAME_REFERENCE ||
                        expr.kind == SyntaxKind.LIST_BP_OR_LIST_CONSTRUCTOR ||
                        expr.kind == SyntaxKind.MAPPING_BP_OR_MAPPING_CONSTRUCTOR) {
                    return SyntaxKind.MAPPING_BP_OR_MAPPING_CONSTRUCTOR;
                }
                return SyntaxKind.MAPPING_CONSTRUCTOR;
            case SPREAD_FIELD:
            case COMPUTED_NAME_FIELD:
                return SyntaxKind.MAPPING_CONSTRUCTOR;
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
            case SIMPLE_NAME_REFERENCE:
            case QUALIFIED_NAME_REFERENCE:
            case LIST_BP_OR_LIST_CONSTRUCTOR:
            case REST_BINDING_PATTERN: // ambiguous with spread-field in mapping-constructor
            default:
                return SyntaxKind.MAPPING_BP_OR_MAPPING_CONSTRUCTOR;
        }
    }

    private STNode parseMappingBindingPatternOrMappingConstructor(STNode openBrace, List<STNode> members,
                                                                  STNode closeBrace) {
        endContext();
        return new STAmbiguousCollectionNode(SyntaxKind.MAPPING_BP_OR_MAPPING_CONSTRUCTOR, openBrace, members,
                closeBrace);
    }

    private STNode parseAsMappingBindingPattern(STNode openBrace, List<STNode> members, STNode member) {
        members.add(member);
        members = getBindingPatternsList(members);
        // create mapping binding pattern
        switchContext(ParserRuleContext.MAPPING_BINDING_PATTERN);
        return parseMappingBindingPattern(openBrace, members, member);
    }

    /**
     * Parse list binding pattern or list constructor.
     *
     * @return Parsed node
     */
    private STNode parseListBindingPatternOrListConstructor() {
        startContext(ParserRuleContext.BRACKETED_LIST);
        STNode openBracket = parseOpenBracket();
        List<STNode> memberList = new ArrayList<>();
        return parseListBindingPatternOrListConstructor(openBracket, memberList, false);
    }

    private STNode parseListBindingPatternOrListConstructor(STNode openBracket, List<STNode> memberList,
                                                            boolean isRoot) {
        // Parse the members
        STToken nextToken = peek();
        while (!isBracketedListEnd(nextToken.kind)) {
            // Parse member
            STNode member = parseListBindingPatternOrListConstructorMember(nextToken.kind);
            SyntaxKind currentNodeType = getParsingNodeTypeOfListBPOrListCons(member);

            switch (currentNodeType) {
                case LIST_CONSTRUCTOR:
                    // If the member type was figured out as a list constructor, then parse the
                    // remaining members as list constructor members and be done with it.
                    return parseAsListConstructor(openBracket, memberList, member, isRoot);
                case LIST_BINDING_PATTERN:
                    // If the member type was figured out as a binding pattern, then parse the
                    // remaining members as binding patterns and be done with it.
                    return parseAsListBindingPattern(openBracket, memberList, member, isRoot);
                case LIST_BP_OR_LIST_CONSTRUCTOR:
                default:
                    memberList.add(member);
                    break;
            }

            // Parse separator
            STNode memberEnd = parseBracketedListMemberEnd();
            if (memberEnd == null) {
                break;
            }
            memberList.add(memberEnd);
            nextToken = peek();
        }

        // We reach here if it is still ambiguous, even after parsing the full list.
        STNode closeBracket = parseCloseBracket();
        return parseListBindingPatternOrListConstructor(openBracket, memberList, closeBracket, isRoot);
    }

    private STNode parseListBindingPatternOrListConstructorMember() {
        return parseListBindingPatternOrListConstructorMember(peek().kind);
    }

    private STNode parseListBindingPatternOrListConstructorMember(SyntaxKind nextTokenKind) {
        switch (nextTokenKind) {
            case OPEN_BRACKET_TOKEN:
                // we don't know which one
                return parseListBindingPatternOrListConstructor();
            case IDENTIFIER_TOKEN:
                STNode identifier = parseQualifiedIdentifier(ParserRuleContext.VARIABLE_REF);
                if (isWildcardBP(identifier)) {
                    return getWildcardBindingPattern(identifier);
                }

                // TODO: handle function-binding-pattern
                // we don't know which one
                return parseExpressionRhs(DEFAULT_OP_PRECEDENCE, identifier, false, false);
            case OPEN_BRACE_TOKEN:
                return parseMappingBindingPatterOrMappingConstructor();
            case ELLIPSIS_TOKEN:
                return parseListBindingPatternMember();
            default:
                if (isValidExpressionStart(nextTokenKind, 1)) {
                    return parseExpression();
                }

                Solution solution = recover(peek(), ParserRuleContext.LIST_BP_OR_LIST_CONSTRUCTOR_MEMBER);

                // If the parser recovered by inserting a token, then try to re-parse the same
                // rule with the inserted token. This is done to pick the correct branch
                // to continue the parsing.
                if (solution.action == Action.REMOVE) {
                    return solution.recoveredNode;
                }

                return parseListBindingPatternOrListConstructorMember(solution.tokenKind);
        }
    }

    private SyntaxKind getParsingNodeTypeOfListBPOrListCons(STNode memberNode) {
        switch (memberNode.kind) {
            case CAPTURE_BINDING_PATTERN:
            case LIST_BINDING_PATTERN:
            case REST_BINDING_PATTERN:
            case MAPPING_BINDING_PATTERN:
            case WILDCARD_BINDING_PATTERN:
                return SyntaxKind.LIST_BINDING_PATTERN;
            case SIMPLE_NAME_REFERENCE: // member is a simple type-ref/var-ref
            case LIST_BP_OR_LIST_CONSTRUCTOR: // member is again ambiguous
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
                return SyntaxKind.LIST_BP_OR_LIST_CONSTRUCTOR;
            default:
                return SyntaxKind.LIST_CONSTRUCTOR;
        }
    }

    private STNode parseAsListConstructor(STNode openBracket, List<STNode> memberList, STNode member, boolean isRoot) {
        memberList.add(member);
        memberList = getExpressionList(memberList);

        switchContext(ParserRuleContext.LIST_CONSTRUCTOR);
        STNode expressions = parseOptionalExpressionsList(memberList);
        STNode closeBracket = parseCloseBracket();
        STNode listConstructor =
                STNodeFactory.createListConstructorExpressionNode(openBracket, expressions, closeBracket);
        endContext();

        STNode expr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, listConstructor, false, false);
        if (!isRoot) {
            return expr;
        }

        return parseStatementStartWithExprRhs(expr);

    }

    private STNode parseListBindingPatternOrListConstructor(STNode openBracket, List<STNode> members,
                                                            STNode closeBracket, boolean isRoot) {
        STNode lbpOrListCons;
        switch (peek().kind) {
            case COMMA_TOKEN: // [a, b, c],
            case CLOSE_BRACE_TOKEN: // [a, b, c]}
            case CLOSE_BRACKET_TOKEN:// [a, b, c]]
                if (!isRoot) {
                    endContext();
                    return new STAmbiguousCollectionNode(SyntaxKind.LIST_BP_OR_LIST_CONSTRUCTOR, openBracket, members,
                            closeBracket);
                }
                // fall through
            default:
                if (isValidExprRhsStart(peek().kind, closeBracket.kind)) {
                    members = getExpressionList(members);
                    STNode memberExpressions = STNodeFactory.createNodeList(members);
                    lbpOrListCons = STNodeFactory.createListConstructorExpressionNode(openBracket, memberExpressions,
                            closeBracket);
                    break;
                }

                // Treat everything else as list-binding-pattern
                members = getBindingPatternsList(members);
                STNode bindingPatternsNode = STNodeFactory.createNodeList(members);
                STNode restBindingPattern = STNodeFactory.createEmptyNode();
                lbpOrListCons = STNodeFactory.createListBindingPatternNode(openBracket, bindingPatternsNode,
                        restBindingPattern, closeBracket);
                break;
        }

        endContext();

        if (!isRoot) {
            return lbpOrListCons;
        }

        return parseStmtStartsWithTypedBPOrExprRhs(null, lbpOrListCons);
    }

    private STNode parseMemberRhsInStmtStartWithBrace(STNode identifier, STNode colon, STNode secondIdentifier) {
        STNode typedBPOrExpr =
                parseTypedBindingPatternOrMemberAccess(secondIdentifier, false, true, ParserRuleContext.AMBIGUOUS_STMT);
        if (isExpression(typedBPOrExpr.kind)) {
            return parseMemberWithExprInRhs(identifier, colon, secondIdentifier, typedBPOrExpr);
        }

        switchContext(ParserRuleContext.BLOCK_STMT);
        startContext(ParserRuleContext.VAR_DECL_STMT);
        STNode finalKeyword = STNodeFactory.createEmptyNode();
        STNode annots = STNodeFactory.createEmptyNode();

        // We reach here for something like: "{ foo:bar[". But we started parsing the rhs component
        // starting with "bar". Hence if its a typed-binding-pattern, then merge the "foo:" with
        // the rest of the type-desc.
        STNode qualifiedNameRef = STNodeFactory.createQualifiedNameReferenceNode(identifier, colon, secondIdentifier);
        STNode typeDesc = mergeQualifiedNameWithTypeDesc(qualifiedNameRef,
                ((STTypedBindingPatternNode) typedBPOrExpr).typeDescriptor);
        return parseVarDeclRhs(annots, finalKeyword, typeDesc, false);
    }

    /**
     * Parse a member that starts with "foo:bar[", in a statement starting with a brace.
     *
     * @param identifier First identifier of the statement
     * @param colon Colon that follows the first identifier
     * @param secondIdentifier Identifier that follows the colon
     * @param memberAccessExpr Member access expression
     * @return Parsed node
     */
    private STNode parseMemberWithExprInRhs(STNode identifier, STNode colon, STNode secondIdentifier,
                                            STNode memberAccessExpr) {
        STNode expr = parseExpressionRhs(DEFAULT_OP_PRECEDENCE, memberAccessExpr, false, true);
        switch (peek().kind) {
            case COMMA_TOKEN:
            case CLOSE_BRACE_TOKEN:
                switchContext(ParserRuleContext.EXPRESSION_STATEMENT);
                startContext(ParserRuleContext.MAPPING_CONSTRUCTOR);
                STNode readonlyKeyword = STNodeFactory.createEmptyNode();
                return STNodeFactory.createSpecificFieldNode(readonlyKeyword, identifier, colon, expr);
            case EQUAL_TOKEN:
            case SEMICOLON_TOKEN:
            default:
                switchContext(ParserRuleContext.BLOCK_STMT);
                startContext(ParserRuleContext.EXPRESSION_STATEMENT);
                // stmt start with expr
                STNode qualifiedName =
                        STNodeFactory.createQualifiedNameReferenceNode(identifier, colon, secondIdentifier);
                STNode updatedExpr = mergeQualifiedNameWithExpr(qualifiedName, expr);
                return parseStatementStartWithExprRhs(updatedExpr);
        }
    }

    /**
     * Replace the first identifier of an expression, with a given qualified-identifier.
     * Only expressions that can start with "bar[..]" can reach here.
     *
     * @param qualifiedName Qualified identifier to replace simple identifier
     * @param exprOrAction Expression or action
     * @return Updated expression
     */
    private STNode mergeQualifiedNameWithExpr(STNode qualifiedName, STNode exprOrAction) {
        switch (exprOrAction.kind) {
            case SIMPLE_NAME_REFERENCE:
                return qualifiedName;
            case BINARY_EXPRESSION:
                STBinaryExpressionNode binaryExpr = (STBinaryExpressionNode) exprOrAction;
                STNode newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, binaryExpr.lhsExpr);
                return STNodeFactory.createBinaryExpressionNode(binaryExpr.kind, newLhsExpr, binaryExpr.operator,
                        binaryExpr.rhsExpr);
            case FIELD_ACCESS:
                STFieldAccessExpressionNode fieldAccess = (STFieldAccessExpressionNode) exprOrAction;
                newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, fieldAccess.expression);
                return STNodeFactory.createFieldAccessExpressionNode(newLhsExpr, fieldAccess.dotToken,
                        fieldAccess.fieldName);
            case INDEXED_EXPRESSION:
                STIndexedExpressionNode memberAccess = (STIndexedExpressionNode) exprOrAction;
                newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, memberAccess.containerExpression);
                return STNodeFactory.createIndexedExpressionNode(newLhsExpr, memberAccess.openBracket,
                        memberAccess.keyExpression, memberAccess.closeBracket);
            case TYPE_TEST_EXPRESSION:
                STTypeTestExpressionNode typeTest = (STTypeTestExpressionNode) exprOrAction;
                newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, typeTest.expression);
                return STNodeFactory.createTypeTestExpressionNode(newLhsExpr, typeTest.isKeyword,
                        typeTest.typeDescriptor);
            case ANNOT_ACCESS:
                STAnnotAccessExpressionNode annotAccess = (STAnnotAccessExpressionNode) exprOrAction;
                newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, annotAccess.expression);
                return STNodeFactory.createFieldAccessExpressionNode(newLhsExpr, annotAccess.annotChainingToken,
                        annotAccess.annotTagReference);
            case OPTIONAL_FIELD_ACCESS:
                STOptionalFieldAccessExpressionNode optionalFieldAccess =
                        (STOptionalFieldAccessExpressionNode) exprOrAction;
                newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, optionalFieldAccess.expression);
                return STNodeFactory.createFieldAccessExpressionNode(newLhsExpr,
                        optionalFieldAccess.optionalChainingToken, optionalFieldAccess.fieldName);
            case CONDITIONAL_EXPRESSION:
                STConditionalExpressionNode conditionalExpr = (STConditionalExpressionNode) exprOrAction;
                newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, conditionalExpr.lhsExpression);
                return STNodeFactory.createConditionalExpressionNode(newLhsExpr, conditionalExpr.questionMarkToken,
                        conditionalExpr.middleExpression, conditionalExpr.colonToken, conditionalExpr.endExpression);
            case REMOTE_METHOD_CALL_ACTION:
                STRemoteMethodCallActionNode remoteCall = (STRemoteMethodCallActionNode) exprOrAction;
                newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, remoteCall.expression);
                return STNodeFactory.createRemoteMethodCallActionNode(newLhsExpr, remoteCall.rightArrowToken,
                        remoteCall.methodName, remoteCall.openParenToken, remoteCall.arguments,
                        remoteCall.closeParenToken);
            case ASYNC_SEND_ACTION:
                STAsyncSendActionNode asyncSend = (STAsyncSendActionNode) exprOrAction;
                newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, asyncSend.expression);
                return STNodeFactory.createAsyncSendActionNode(newLhsExpr, asyncSend.rightArrowToken,
                        asyncSend.peerWorker);
            case SYNC_SEND_ACTION:
                STSyncSendActionNode syncSend = (STSyncSendActionNode) exprOrAction;
                newLhsExpr = mergeQualifiedNameWithExpr(qualifiedName, syncSend.expression);
                return STNodeFactory.createAsyncSendActionNode(newLhsExpr, syncSend.syncSendToken, syncSend.peerWorker);
            default:
                return exprOrAction;
        }
    }

    private STNode mergeQualifiedNameWithTypeDesc(STNode qualifiedName, STNode typeDesc) {
        switch (typeDesc.kind) {
            case SIMPLE_NAME_REFERENCE:
                return qualifiedName;
            case ARRAY_TYPE_DESC:
                STArrayTypeDescriptorNode arrayTypeDesc = (STArrayTypeDescriptorNode) typeDesc;
                STNode newMemberType = mergeQualifiedNameWithTypeDesc(qualifiedName, arrayTypeDesc.memberTypeDesc);
                return createArrayTypeDesc(newMemberType, arrayTypeDesc.openBracket, arrayTypeDesc.arrayLength,
                        arrayTypeDesc.closeBracket);
            case UNION_TYPE_DESC:
                STUnionTypeDescriptorNode unionTypeDesc = (STUnionTypeDescriptorNode) typeDesc;
                STNode newlhsType = mergeQualifiedNameWithTypeDesc(qualifiedName, unionTypeDesc.leftTypeDesc);
                return createUnionTypeDesc(newlhsType, unionTypeDesc.pipeToken, unionTypeDesc.rightTypeDesc);
            case INTERSECTION_TYPE_DESC:
                STIntersectionTypeDescriptorNode intersectionTypeDesc = (STIntersectionTypeDescriptorNode) typeDesc;
                newlhsType = mergeQualifiedNameWithTypeDesc(qualifiedName, intersectionTypeDesc.leftTypeDesc);
                return createUnionTypeDesc(newlhsType, intersectionTypeDesc.bitwiseAndToken,
                        intersectionTypeDesc.rightTypeDesc);
            case OPTIONAL_TYPE_DESC:
                STOptionalTypeDescriptorNode optionalType = (STOptionalTypeDescriptorNode) typeDesc;
                newMemberType = mergeQualifiedNameWithTypeDesc(qualifiedName, optionalType.typeDescriptor);
                return STNodeFactory.createOptionalTypeDescriptorNode(newMemberType, optionalType.questionMarkToken);
            default:
                return typeDesc;
        }
    }

    // ---------------------- Convert ambiguous nodes to a specific node --------------------------

    private List<STNode> getTypeDescList(List<STNode> ambiguousList) {
        List<STNode> typeDescList = new ArrayList<>();
        for (STNode item : ambiguousList) {
            typeDescList.add(getTypeDescFromExpr(item));
        }

        return typeDescList;
    }

    /**
     * Create a type-desc out of an expression.
     *
     * @param expression Expression
     * @return Type descriptor
     */
    private STNode getTypeDescFromExpr(STNode expression) {
        switch (expression.kind) {
            case INDEXED_EXPRESSION:
                return parseArrayTypeDescriptorNode((STIndexedExpressionNode) expression);
            case BASIC_LITERAL:
            case DECIMAL_INTEGER_LITERAL:
            case HEX_INTEGER_LITERAL:
            case STRING_LITERAL:
            case NULL_KEYWORD:
            case TRUE_KEYWORD:
            case FALSE_KEYWORD:
            case DECIMAL_FLOATING_POINT_LITERAL:
            case HEX_FLOATING_POINT_LITERAL:
                return STNodeFactory.createSingletonTypeDescriptorNode(expression);
            case TYPE_REFERENCE_TYPE_DESC:
                // TODO: this is a temporary workaround
                return ((STTypeReferenceTypeDescNode) expression).typeRef;
            case BRACED_EXPRESSION:
                STBracedExpressionNode bracedExpr = (STBracedExpressionNode) expression;
                STNode typeDesc = getTypeDescFromExpr(bracedExpr.expression);
                return STNodeFactory.createParenthesisedTypeDescriptorNode(bracedExpr.openParen, typeDesc,
                        bracedExpr.closeParen);
            case NIL_LITERAL:
                STNilLiteralNode nilLiteral = (STNilLiteralNode) expression;
                return STNodeFactory.createNilTypeDescriptorNode(nilLiteral.openParenToken, nilLiteral.closeParenToken);
            case BRACKETED_LIST:
            case LIST_BP_OR_LIST_CONSTRUCTOR:
                STAmbiguousCollectionNode innerList = (STAmbiguousCollectionNode) expression;
                STNode memberTypeDescs = STNodeFactory.createNodeList(getTypeDescList(innerList.members));
                return STNodeFactory.createTupleTypeDescriptorNode(innerList.collectionStartToken, memberTypeDescs,
                        innerList.collectionEndToken);
            case BINARY_EXPRESSION:
                STBinaryExpressionNode binaryExpr = (STBinaryExpressionNode) expression;
                switch (binaryExpr.operator.kind) {
                    case PIPE_TOKEN:
                        STNode lhsTypeDesc = getTypeDescFromExpr(binaryExpr.lhsExpr);
                        STNode rhsTypeDesc = getTypeDescFromExpr(binaryExpr.rhsExpr);
                        return createUnionTypeDesc(lhsTypeDesc, binaryExpr.operator, rhsTypeDesc);
                    case BITWISE_AND_TOKEN:
                        lhsTypeDesc = getTypeDescFromExpr(binaryExpr.lhsExpr);
                        rhsTypeDesc = getTypeDescFromExpr(binaryExpr.rhsExpr);
                        return createIntersectionTypeDesc(lhsTypeDesc, binaryExpr.operator, rhsTypeDesc);
                    default:
                        break;
                }
                return expression;
            case UNARY_EXPRESSION:
                return STNodeFactory.createSingletonTypeDescriptorNode(expression);
            case SIMPLE_NAME_REFERENCE:
            case QUALIFIED_NAME_REFERENCE:
            default:
                return expression;
        }
    }

    private List<STNode> getBindingPatternsList(List<STNode> ambibuousList) {
        List<STNode> bindingPatterns = new ArrayList<STNode>();
        for (STNode item : ambibuousList) {
            bindingPatterns.add(getBindingPattern(item));
        }
        return bindingPatterns;
    }

    private STNode getBindingPattern(STNode ambiguousNode) {
        if (isEmpty(ambiguousNode)) {
            return ambiguousNode;
        }

        switch (ambiguousNode.kind) {
            case SIMPLE_NAME_REFERENCE:
                STNode varName = ((STSimpleNameReferenceNode) ambiguousNode).name;
                return createCaptureOrWildcardBP(varName);
            case QUALIFIED_NAME_REFERENCE:
                STQualifiedNameReferenceNode qualifiedName = (STQualifiedNameReferenceNode) ambiguousNode;
                STNode fieldName = STNodeFactory.createSimpleNameReferenceNode(qualifiedName.modulePrefix);
                return STNodeFactory.createFieldBindingPatternFullNode(fieldName, qualifiedName.colon,
                        getBindingPattern(qualifiedName.identifier));
            case BRACKETED_LIST:
            case LIST_BP_OR_LIST_CONSTRUCTOR:
                STAmbiguousCollectionNode innerList = (STAmbiguousCollectionNode) ambiguousNode;
                STNode memberBindingPatterns = STNodeFactory.createNodeList(getBindingPatternsList(innerList.members));
                STNode restBindingPattern = STNodeFactory.createEmptyNode();
                return STNodeFactory.createListBindingPatternNode(innerList.collectionStartToken, memberBindingPatterns,
                        restBindingPattern, innerList.collectionEndToken);
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
                innerList = (STAmbiguousCollectionNode) ambiguousNode;
                List<STNode> bindingPatterns = new ArrayList<>();
                restBindingPattern = STNodeFactory.createEmptyNode();
                for (int i = 0; i < innerList.members.size(); i++) {
                    STNode bp = getBindingPattern(innerList.members.get(i));
                    if (bp.kind == SyntaxKind.REST_BINDING_PATTERN) {
                        restBindingPattern = bp;
                        break;
                    }
                    bindingPatterns.add(bp);
                }
                memberBindingPatterns = STNodeFactory.createNodeList(bindingPatterns);
                return STNodeFactory.createMappingBindingPatternNode(innerList.collectionStartToken,
                        memberBindingPatterns, restBindingPattern, innerList.collectionEndToken);
            case SPECIFIC_FIELD:
                STSpecificFieldNode field = (STSpecificFieldNode) ambiguousNode;
                fieldName = STNodeFactory.createSimpleNameReferenceNode(field.fieldName);
                if (field.valueExpr == null) {
                    return STNodeFactory.createFieldBindingPatternVarnameNode(fieldName);
                }
                return STNodeFactory.createFieldBindingPatternFullNode(fieldName, field.colon,
                        getBindingPattern(field.valueExpr));
            case FUNCTION_CALL:
                STFunctionCallExpressionNode funcCall = (STFunctionCallExpressionNode) ambiguousNode;
                STNode args = funcCall.arguments;
                int size = args.bucketCount();
                bindingPatterns = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    STNode arg = args.childInBucket(i);
                    bindingPatterns.add(getBindingPattern(arg));
                }

                STNode argListBindingPatterns = STNodeFactory.createNodeList(bindingPatterns);
                return STNodeFactory.createFunctionalBindingPatternNode(funcCall.functionName, funcCall.openParenToken,
                        argListBindingPatterns, funcCall.closeParenToken);
            case POSITIONAL_ARG:
                STPositionalArgumentNode positionalArg = (STPositionalArgumentNode) ambiguousNode;
                return getBindingPattern(positionalArg.expression);
            case NAMED_ARG:
                STNamedArgumentNode namedArg = (STNamedArgumentNode) ambiguousNode;
                return STNodeFactory.createNamedArgBindingPatternNode(namedArg.argumentName, namedArg.equalsToken,
                        getBindingPattern(namedArg.expression));
            case REST_ARG:
                STRestArgumentNode restArg = (STRestArgumentNode) ambiguousNode;
                return STNodeFactory.createRestBindingPatternNode(restArg.ellipsis, restArg.expression);
            default:
                return ambiguousNode;
        }
    }

    private List<STNode> getExpressionList(List<STNode> ambibuousList) {
        List<STNode> exprList = new ArrayList<STNode>();
        for (STNode item : ambibuousList) {
            exprList.add(getExpression(item));
        }
        return exprList;
    }

    private STNode getExpression(STNode ambiguousNode) {
        if (isEmpty(ambiguousNode)) {
            return ambiguousNode;
        }

        switch (ambiguousNode.kind) {
            case BRACKETED_LIST:
            case LIST_BP_OR_LIST_CONSTRUCTOR:
            case TUPLE_TYPE_DESC_OR_LIST_CONST:
                STAmbiguousCollectionNode innerList = (STAmbiguousCollectionNode) ambiguousNode;
                STNode memberExprs = STNodeFactory.createNodeList(getExpressionList(innerList.members));
                return STNodeFactory.createListConstructorExpressionNode(innerList.collectionStartToken, memberExprs,
                        innerList.collectionEndToken);
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
                innerList = (STAmbiguousCollectionNode) ambiguousNode;
                List<STNode> fieldList = new ArrayList<>();
                for (int i = 0; i < innerList.members.size(); i++) {
                    STNode field = innerList.members.get(i);
                    STNode fieldNode;
                    if (field.kind == SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                        STQualifiedNameReferenceNode qualifiedNameRefNode = (STQualifiedNameReferenceNode) field;
                        STNode readOnlyKeyword = STNodeFactory.createEmptyNode();
                        STNode fieldName = qualifiedNameRefNode.modulePrefix;
                        STNode colon = qualifiedNameRefNode.colon;
                        STNode valueExpr = getExpression(qualifiedNameRefNode.identifier);
                        fieldNode = STNodeFactory.createSpecificFieldNode(readOnlyKeyword, fieldName, colon, valueExpr);
                    } else {
                        fieldNode = getExpression(field);
                    }

                    fieldList.add(fieldNode);
                }
                STNode fields = STNodeFactory.createNodeList(fieldList);
                return STNodeFactory.createMappingConstructorExpressionNode(innerList.collectionStartToken, fields,
                        innerList.collectionEndToken);
            case REST_BINDING_PATTERN:
                STRestBindingPatternNode restBindingPattern = (STRestBindingPatternNode) ambiguousNode;
                return STNodeFactory.createSpreadFieldNode(restBindingPattern.ellipsisToken,
                        restBindingPattern.variableName);
            case SPECIFIC_FIELD:
                // Specific field is used to represent ambiguous scenarios. Hence it needs to be re-written.
                STSpecificFieldNode field = (STSpecificFieldNode) ambiguousNode;
                return STNodeFactory.createSpecificFieldNode(field.readonlyKeyword, field.fieldName, field.colon,
                        getExpression(field.valueExpr));
            case SIMPLE_NAME_REFERENCE:
            case QUALIFIED_NAME_REFERENCE:
            default:
                return ambiguousNode;
        }
    }

    private STNode getMappingField(STNode identifier, STNode colon, STNode bindingPatternOrExpr) {
        STNode simpleNameRef = STNodeFactory.createSimpleNameReferenceNode(identifier);
        switch (bindingPatternOrExpr.kind) {
            case LIST_BINDING_PATTERN:
            case MAPPING_BINDING_PATTERN:
                return STNodeFactory.createFieldBindingPatternFullNode(simpleNameRef, colon, bindingPatternOrExpr);
            case LIST_CONSTRUCTOR:
            case MAPPING_CONSTRUCTOR:
                STNode readonlyKeyword = STNodeFactory.createEmptyNode();
                return STNodeFactory.createSpecificFieldNode(readonlyKeyword, simpleNameRef, colon, identifier);
            case LIST_BP_OR_LIST_CONSTRUCTOR:
            case MAPPING_BP_OR_MAPPING_CONSTRUCTOR:
            default:
                // If ambiguous, return an specific node, since it is used to represent any
                // ambiguous mapping field
                readonlyKeyword = STNodeFactory.createEmptyNode();
                return STNodeFactory.createSpecificFieldNode(readonlyKeyword, identifier, colon, bindingPatternOrExpr);
        }
    }

    // ----------------------------------------- ~ End of Parser ~ ----------------------------------------

    // NOTE: Please add any new methods to the relevant section of the class. Binding patterns related code is the
    // last section of the class.
}
