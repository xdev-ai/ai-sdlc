package provider

import (
	"context"
	"fmt"
	"github.com/hashicorp/terraform-plugin-framework/datasource"
	"github.com/hashicorp/terraform-plugin-framework/datasource/schema"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

type riskSnapshotModel struct {
	ID             types.String `tfsdk:"id"`
	Score          types.Int64  `tfsdk:"score"`
	Band           types.String `tfsdk:"band"`
	FormulaVersion types.String `tfsdk:"formula_version"`
	ComputedAt     types.String `tfsdk:"computed_at"`
}
type riskSnapshotDataSource struct{ client *client }

func newRiskSnapshotDataSource() datasource.DataSource { return &riskSnapshotDataSource{} }
func (d *riskSnapshotDataSource) Metadata(_ context.Context, _ datasource.MetadataRequest, response *datasource.MetadataResponse) {
	response.TypeName = "aisdlc_risk_snapshot"
}
func (d *riskSnapshotDataSource) Schema(_ context.Context, _ datasource.SchemaRequest, response *datasource.SchemaResponse) {
	response.Schema = schema.Schema{Attributes: map[string]schema.Attribute{
		"id": schema.StringAttribute{Computed: true}, "score": schema.Int64Attribute{Computed: true}, "band": schema.StringAttribute{Computed: true}, "formula_version": schema.StringAttribute{Computed: true}, "computed_at": schema.StringAttribute{Computed: true},
	}}
}
func (d *riskSnapshotDataSource) Configure(_ context.Context, request datasource.ConfigureRequest, response *datasource.ConfigureResponse) {
	if request.ProviderData == nil {
		return
	}
	client, ok := request.ProviderData.(*client)
	if !ok {
		response.Diagnostics.AddError("Unexpected provider data", "The AI-SDLC provider supplied an invalid API client.")
		return
	}
	d.client = client
}
func (d *riskSnapshotDataSource) Read(ctx context.Context, _ datasource.ReadRequest, response *datasource.ReadResponse) {
	var output struct {
		ID             string `json:"id"`
		Score          int64  `json:"score"`
		Band           string `json:"band"`
		FormulaVersion string `json:"formulaVersion"`
		ComputedAt     string `json:"computedAt"`
	}
	if err := d.client.request(ctx, "GET", fmt.Sprintf("/api/v1/projects/%s/risk-intelligence/latest", d.client.projectID), nil, &output); err != nil {
		response.Diagnostics.AddError("Unable to read latest risk snapshot", err.Error())
		return
	}
	response.Diagnostics.Append(response.State.Set(ctx, riskSnapshotModel{ID: types.StringValue(output.ID), Score: types.Int64Value(output.Score), Band: types.StringValue(output.Band), FormulaVersion: types.StringValue(output.FormulaVersion), ComputedAt: types.StringValue(output.ComputedAt)})...)
}

var _ datasource.DataSource = &riskSnapshotDataSource{}
var _ datasource.DataSourceWithConfigure = &riskSnapshotDataSource{}
