package provider

import (
	"context"
	"github.com/hashicorp/terraform-plugin-framework/datasource"
	"github.com/hashicorp/terraform-plugin-framework/provider"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/types"
	"os"
)

type providerModel struct {
	APIURL    types.String `tfsdk:"api_url"`
	Token     types.String `tfsdk:"token"`
	ProjectID types.String `tfsdk:"project_id"`
}
type aiSdlcProvider struct{ version string }

func New(version string) func() provider.Provider {
	return func() provider.Provider { return &aiSdlcProvider{version: version} }
}
func (p *aiSdlcProvider) Metadata(_ context.Context, _ provider.MetadataRequest, response *provider.MetadataResponse) {
	response.TypeName = "aisdlc"
	response.Version = p.version
}
func (p *aiSdlcProvider) Schema(_ context.Context, _ provider.SchemaRequest, response *provider.SchemaResponse) {
	response.Schema = providerSchema()
}
func (p *aiSdlcProvider) Resources(_ context.Context) []func() resource.Resource {
	return []func() resource.Resource{newNotificationChannelResource}
}
func (p *aiSdlcProvider) DataSources(_ context.Context) []func() datasource.DataSource {
	return []func() datasource.DataSource{newRiskSnapshotDataSource}
}
func (p *aiSdlcProvider) Configure(ctx context.Context, request provider.ConfigureRequest, response *provider.ConfigureResponse) {
	var data providerModel
	response.Diagnostics.Append(request.Config.Get(ctx, &data)...)
	if response.Diagnostics.HasError() {
		return
	}
	apiURL := data.APIURL.ValueString()
	if apiURL == "" {
		apiURL = os.Getenv("AISDLC_API_URL")
	}
	token := data.Token.ValueString()
	if token == "" {
		token = os.Getenv("AISDLC_TOKEN")
	}
	projectID := data.ProjectID.ValueString()
	if projectID == "" {
		projectID = os.Getenv("AISDLC_PROJECT_ID")
	}
	if apiURL == "" || token == "" || projectID == "" {
		response.Diagnostics.AddError("Missing AI-SDLC provider configuration", "Set api_url, token, and project_id or their AISDLC_API_URL, AISDLC_TOKEN, and AISDLC_PROJECT_ID environment variables.")
		return
	}
	client := &client{baseURL: apiURL, token: token, projectID: projectID}
	response.DataSourceData, response.ResourceData = client, client
}

var _ provider.Provider = &aiSdlcProvider{}
