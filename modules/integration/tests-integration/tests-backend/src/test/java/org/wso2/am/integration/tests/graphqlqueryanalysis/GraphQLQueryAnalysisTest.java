package org.wso2.am.integration.tests.graphqlqueryanalysis;
import java.io.BufferedWriter;
import java.io.File;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.*;
import org.wso2.am.admin.clients.registry.ResourceAdminServiceClient;
import org.wso2.am.integration.clients.admin.api.dto.*;

import org.wso2.am.integration.clients.publisher.api.v1.dto.*;
import org.wso2.am.integration.clients.store.api.ApiException;
import org.wso2.am.integration.clients.store.api.ApiResponse;
import org.wso2.am.integration.clients.store.api.v1.dto.ApplicationDTO;
import org.wso2.am.integration.clients.store.api.v1.dto.ApplicationKeyDTO;
import org.wso2.am.integration.clients.store.api.v1.dto.ApplicationKeyGenerateRequestDTO;
import org.wso2.am.integration.test.Constants;
import org.wso2.am.integration.test.impl.RestAPIAdminImpl;
import org.wso2.am.integration.test.impl.RestAPIStoreImpl;
import org.wso2.am.integration.test.utils.UserManagementUtils;
import org.wso2.am.integration.test.utils.base.APIMIntegrationBaseTest;
import org.wso2.am.integration.test.utils.base.APIMIntegrationConstants;
import org.wso2.am.integration.test.utils.bean.*;
import org.wso2.am.integration.test.utils.clients.APIPublisherRestClient;
import org.wso2.am.integration.test.utils.clients.APIStoreRestClient;
import org.wso2.am.integration.test.utils.clients.AdminDashboardRestClient;
import org.wso2.am.integration.test.utils.http.HTTPSClientUtils;
import org.wso2.am.integration.tests.api.lifecycle.APIManagerLifecycleBaseTest;

import org.wso2.carbon.apimgt.api.WorkflowStatus;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.automation.engine.context.TestUserMode;
import org.wso2.carbon.automation.engine.context.beans.User;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;
import org.wso2.carbon.integration.common.admin.client.UserManagementClient;

import javax.ws.rs.core.Response;
import java.io.FileWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;

public class GraphQLQueryAnalysisTest extends APIMIntegrationBaseTest {

    private  AdminDashboardRestClient adminDashboardRestClient;
    private String ADMIN_ROLE = "admin";
    private String USER_ADMIN = "smith";
    private final String ALLOWED_ROLE = "admin";
    private RestAPIAdminImpl restAPIAdminUser;

    private final String GRAPHQL_API_NAME = "CountriesGraphqlAPI";
    private final String API_CONTEXT = "info";
    private final String API_VERSION_1_0_0 = "1.0.0";
    private final String END_POINT_URL = "https://localhost:9943/am-graphQL-sample/api/graphql/";
    private final String RESPONSE_DATA = "[{\"name\":\"Afrikaans\",\"code\":\"af\"},{\"name\":\"Amharic\",\"code\":\"am\"}," +
            "{\"name\":\"Arabic\",\"code\":\"ar\"},{\"name\":\"Aymara\",\"code\":\"ay\"},{\"name\":\"Azerbaijani\"," +
            "\"code\":\"az\"},{\"name\":\"Belarusian\",\"code\":\"be\"}]";
    private String schemaDefinition;
    private String graphqlAPIId;
    private String applicationId;



    @Factory(dataProvider = "userModeDataProvider")
    public GraphQLQueryAnalysisTest(TestUserMode userMode) {
        this.userMode = userMode;
    }

    @DataProvider
    public static Object[][] userModeDataProvider() {
        return new Object[][]{new Object[]{TestUserMode.SUPER_TENANT_ADMIN}};
    }

    @BeforeClass(alwaysRun = true)
    public void setEnvironment() throws Exception {
        super.init(userMode);
        adminDashboardRestClient = new AdminDashboardRestClient(getPublisherURLHttps());
        userManagementClient = new UserManagementClient(keyManagerContext.getContextUrls().getBackEndUrl(),
                keyManagerContext.getContextTenant().getTenantAdmin().getUserName(),
                keyManagerContext.getContextTenant().getTenantAdmin().getPassword());

        userManagementClient.addUser(USER_ADMIN, "admin", new String[]{ALLOWED_ROLE}, ADMIN_ROLE);

        // add new Subscription throttling policy
        SubscriptionThrottlePolicyDTO subscriptionThrottlePolicyDTO = new SubscriptionThrottlePolicyDTO();
        createNewSubscriptionPolicyObject(subscriptionThrottlePolicyDTO);
        restAPIAdminUser = new RestAPIAdminImpl(USER_ADMIN, "admin", "carbon.super",
                adminURLHttps);
        org.wso2.am.integration.test.HttpResponse response = restAPIAdminUser.addSubscriptionPolicy(subscriptionThrottlePolicyDTO,"application/json");
        assertEquals(response.getResponseCode(), 201);


        //create  and publish GraphQL API
        schemaDefinition = IOUtils.toString(
                getClass().getClassLoader().getResourceAsStream("graphql" + File.separator + "schema.graphql"),
                "UTF-8");

        File file = getTempFileWithContent(schemaDefinition);
        GraphQLValidationResponseDTO responseApiDto = restAPIPublisher.validateGraphqlSchemaDefinition(file);
        GraphQLValidationResponseGraphQLInfoDTO graphQLInfo = responseApiDto.getGraphQLInfo();
        String arrayToJson = new ObjectMapper().writeValueAsString(graphQLInfo.getOperations());
        JSONArray operations = new JSONArray(arrayToJson);
        ArrayList<String> environment = new ArrayList<String>();
        environment.add("Production and Sandbox");

        ArrayList<String> policies = new ArrayList<String>();
        policies.add("Platinum");
        policies.add("Unlimited");

        JSONObject additionalPropertiesObj = new JSONObject();
        additionalPropertiesObj.put("name", GRAPHQL_API_NAME);
        additionalPropertiesObj.put("context", API_CONTEXT);
        additionalPropertiesObj.put("version", API_VERSION_1_0_0);

        JSONObject url = new JSONObject();
        url.put("url", END_POINT_URL);
        JSONObject endpointConfig = new JSONObject();
        endpointConfig.put("endpoint_type", "http");
        endpointConfig.put("sandbox_endpoints", url);
        endpointConfig.put("production_endpoints", url);
        additionalPropertiesObj.put("endpointConfig", endpointConfig);
        additionalPropertiesObj.put("gatewayEnvironments", environment);
        additionalPropertiesObj.put("policies", policies);
        additionalPropertiesObj.put("operations", operations);

        // create Graphql API
        APIDTO apidto = restAPIPublisher.importGraphqlSchemaDefinition(file, additionalPropertiesObj.toString());
        graphqlAPIId = apidto.getId();
        HttpResponse createdApiResponse = restAPIPublisher.getAPI(graphqlAPIId);
        assertEquals(Response.Status.OK.getStatusCode(), createdApiResponse.getResponseCode(),
                GRAPHQL_API_NAME + " API creation is failed");

        // update GraphQL API tier
        //apidto.setApiThrottlingPolicy("Platinum");
        //APIDTO updatedAPI = restAPIPublisher.updateAPI(apidto, graphqlAPIId);
        //HttpResponse updatedApiResponse = restAPIPublisher.getAPI(graphqlAPIId);
        //assertEquals(Response.Status.OK.getStatusCode(), updatedApiResponse.getResponseCode(),
               // GRAPHQL_API_NAME + " API updated is failed");

        // publish api
        restAPIPublisher.changeAPILifeCycleStatus(graphqlAPIId, Constants.PUBLISHED);
        waitForAPIDeploymentSync(user.getUserName(), GRAPHQL_API_NAME, API_VERSION_1_0_0,
                APIMIntegrationConstants.IS_API_EXISTS);

    }

    /*@Test(groups = {"wso2.am"}, description = "API invocation using oauth App")
    public void testInvokeGraphqlAPIUsingOAuthApplication() throws Exception {
        String graphqlOAUTHAppName = "CountriesOauthAPP";
        createGraphqlAppAndSubscribeToAPI(graphqlOAUTHAppName,"OAUTH");

        // generate token
        ArrayList<String> grantTypes = new ArrayList<>();
        grantTypes.add(APIMIntegrationConstants.GRANT_TYPE.CLIENT_CREDENTIAL);
        ApplicationKeyDTO applicationKeyDTO = restAPIStore.generateKeys(applicationId, "36000", "",
                ApplicationKeyGenerateRequestDTO.KeyTypeEnum.PRODUCTION, null, grantTypes);
        String accessToken = applicationKeyDTO.getToken().getAccessToken();

        String invokeURL = getAPIInvocationURLHttp(API_CONTEXT, API_VERSION_1_0_0) + "/";
        Map<String, String> requestHeaders = new HashMap<String, String>();
        JSONObject queryObject = new JSONObject();
        queryObject.put("query", "{languages{code name}}");
        requestHeaders.put(APIMIntegrationConstants.AUTHORIZATION_HEADER, "Bearer " + accessToken);
        requestHeaders.put("Content-Type",  "application/json");
        HttpResponse serviceResponse = HTTPSClientUtils.doPost(invokeURL, requestHeaders, queryObject.toString());

        Assert.assertEquals(serviceResponse.getResponseCode(), HttpStatus.SC_OK,
                "Response code is not as expected");
        Assert.assertEquals(serviceResponse.getData(), RESPONSE_DATA, "Response data is not as expected");
    }*/

    @Test(groups = {"wso2.am"}, description = "Add GraphQL Complexity Values in Publisher Portal")
    public void testAddGraphQLComplexity() throws Exception {
        //Get GraphQL Schema Type List
        GraphQLSchemaTypeListDTO graphQLSchemaTypeList = restAPIPublisher.getGraphQLSchemaTypeList(graphqlAPIId);
        HttpResponse response = restAPIPublisher.getGraphQLSchemaTypeListResponse(graphqlAPIId);
        assertEquals(Response.Status.OK.getStatusCode(), response.getResponseCode());

        // add GraphQL Complexity Details
        List<GraphQLSchemaTypeDTO> list = graphQLSchemaTypeList.getTypeList();
        System.out.println(list);
        List<GraphQLCustomComplexityInfoDTO> complexityList = new ArrayList<GraphQLCustomComplexityInfoDTO>();;
        for (GraphQLSchemaTypeDTO graphQLSchemaTypeDTO : list) {
            List<String> fieldList = graphQLSchemaTypeDTO.getFieldList();
            for(String field : fieldList) {
                GraphQLCustomComplexityInfoDTO graphQLCustomComplexityInfoDTO = new GraphQLCustomComplexityInfoDTO();
                graphQLCustomComplexityInfoDTO.setType(graphQLSchemaTypeDTO.getType());
                graphQLCustomComplexityInfoDTO.setField(field);
                graphQLCustomComplexityInfoDTO.setComplexityValue(2);
                System.out.println(graphQLCustomComplexityInfoDTO);
                complexityList.add(graphQLCustomComplexityInfoDTO);
            }
        }
        GraphQLQueryComplexityInfoDTO graphQLQueryComplexityInfoDTO = new GraphQLQueryComplexityInfoDTO();
        graphQLQueryComplexityInfoDTO.setList(complexityList);
        restAPIPublisher.addGraphQLComplexityDetails(graphQLQueryComplexityInfoDTO,graphqlAPIId);

        //Get GraphQLComplexity
        HttpResponse complexityResponse = restAPIPublisher.getGraphQLComplexityResponse(graphqlAPIId);
        assertEquals(Response.Status.OK.getStatusCode(), complexityResponse.getResponseCode());

    }


    public SubscriptionThrottlePolicyDTO createNewSubscriptionPolicyObject(SubscriptionThrottlePolicyDTO subscriptionThrottlePolicyDTO){
        subscriptionThrottlePolicyDTO.setPolicyId("0c6439fd-9b16-3c2e-be6e-1086e0b9aa92");
        subscriptionThrottlePolicyDTO.setPolicyName("Platinum");
        subscriptionThrottlePolicyDTO.setDisplayName("Platinum");
        subscriptionThrottlePolicyDTO.setDescription("Platinum");
        subscriptionThrottlePolicyDTO.setRateLimitCount(1000);
        subscriptionThrottlePolicyDTO.setRateLimitTimeUnit("min");
        subscriptionThrottlePolicyDTO.setBillingPlan("COMMERCIAL");
        subscriptionThrottlePolicyDTO.setStopOnQuotaReach(true);
        subscriptionThrottlePolicyDTO.setIsDeployed(true);
        subscriptionThrottlePolicyDTO.setGraphQLMaxComplexity(6);
        subscriptionThrottlePolicyDTO.setGraphQLMaxDepth(5);

        ThrottleLimitDTO throttleLimitDTO = new ThrottleLimitDTO();
        throttleLimitDTO.setType(ThrottleLimitDTO.TypeEnum.valueOf("REQUESTCOUNTLIMIT"));
        RequestCountLimitDTO requestCountLimitDTO = new RequestCountLimitDTO();
        requestCountLimitDTO.setRequestCount(Long.valueOf(1000));
        requestCountLimitDTO.setTimeUnit("min");
        requestCountLimitDTO.setUnitTime(10);
        throttleLimitDTO.setRequestCount(requestCountLimitDTO);

        subscriptionThrottlePolicyDTO.setDefaultLimit(throttleLimitDTO);
        return subscriptionThrottlePolicyDTO;
    }

    private File getTempFileWithContent(String schema) throws Exception {
        File temp = File.createTempFile("schema", ".graphql");
        temp.deleteOnExit();
        BufferedWriter out = new BufferedWriter(new FileWriter(temp));
        out.write(schema);
        out.close();
        return temp;
    }

    private void createGraphqlAppAndSubscribeToAPI(String appName, String tokenType) throws ApiException {
        ApplicationDTO applicationDTO = restAPIStore.addApplicationWithTokenType(appName,
                APIMIntegrationConstants.APPLICATION_TIER.UNLIMITED, "",
                "test app for countries API", tokenType);
        applicationId = applicationDTO.getApplicationId();
        restAPIStore.subscribeToAPI(graphqlAPIId, applicationId, "Platinum");
    }

    @AfterClass(alwaysRun = true)
    public void destroy() throws Exception {
        userManagementClient.deleteUser(USER_ADMIN);
        super.cleanUp();
    }
}
