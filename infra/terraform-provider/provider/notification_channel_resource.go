package provider

import (
	"context"
	"fmt"
	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

type notificationChannelModel struct {
	ID           types.String `tfsdk:"id"`
	Type         types.String `tfsdk:"type"`
	Name         types.String `tfsdk:"name"`
	Destination  types.String `tfsdk:"destination"`
	SharedSecret types.String `tfsdk:"shared_secret"`
	Enabled      types.Bool   `tfsdk:"enabled"`
}
type notificationChannelResource struct{ client *client }

func newNotificationChannelResource() resource.Resource { return &notificationChannelResource{} }
func (r *notificationChannelResource) Metadata(_ context.Context, _ resource.MetadataRequest, response *resource.MetadataResponse) {
	response.TypeName = "aisdlc_notification_channel"
}
func (r *notificationChannelResource) Schema(_ context.Context, _ resource.SchemaRequest, response *resource.SchemaResponse) {
	response.Schema = schema.Schema{Attributes: map[string]schema.Attribute{
		"id": schema.StringAttribute{Computed: true}, "type": schema.StringAttribute{Required: true}, "name": schema.StringAttribute{Required: true}, "destination": schema.StringAttribute{Required: true, Sensitive: true}, "shared_secret": schema.StringAttribute{Optional: true, Sensitive: true}, "enabled": schema.BoolAttribute{Optional: true, Computed: true},
	}}
}
func (r *notificationChannelResource) Configure(_ context.Context, request resource.ConfigureRequest, response *resource.ConfigureResponse) {
	if request.ProviderData == nil {
		return
	}
	providerClient, ok := request.ProviderData.(*client)
	if !ok {
		response.Diagnostics.AddError("Unexpected provider data", "The AI-SDLC provider supplied an invalid API client.")
		return
	}
	r.client = providerClient
}
func (r *notificationChannelResource) Create(ctx context.Context, request resource.CreateRequest, response *resource.CreateResponse) {
	var plan notificationChannelModel
	response.Diagnostics.Append(request.Plan.Get(ctx, &plan)...)
	if response.Diagnostics.HasError() {
		return
	}
	var created struct {
		ID string `json:"id"`
	}
	err := r.client.request(ctx, "POST", fmt.Sprintf("/api/v1/projects/%s/notification-channels", r.client.projectID), map[string]any{"type": plan.Type.ValueString(), "name": plan.Name.ValueString(), "destination": plan.Destination.ValueString(), "sharedSecret": optionalString(plan.SharedSecret)}, &created)
	if err != nil {
		response.Diagnostics.AddError("Unable to create notification channel", err.Error())
		return
	}
	plan.ID = types.StringValue(created.ID)
	if plan.Enabled.IsNull() || plan.Enabled.IsUnknown() {
		plan.Enabled = types.BoolValue(true)
	}
	response.Diagnostics.Append(response.State.Set(ctx, plan)...)
	if plan.Enabled.ValueBool() == false {
		r.setEnabled(ctx, plan.ID.ValueString(), false, &response.Diagnostics)
	}
}
func (r *notificationChannelResource) Read(ctx context.Context, request resource.ReadRequest, response *resource.ReadResponse) {
	var state notificationChannelModel
	response.Diagnostics.Append(request.State.Get(ctx, &state)...)
	if response.Diagnostics.HasError() {
		return
	}
	var channels []struct {
		ID      string `json:"id"`
		Type    string `json:"type"`
		Name    string `json:"name"`
		Enabled bool   `json:"enabled"`
	}
	if err := r.client.request(ctx, "GET", fmt.Sprintf("/api/v1/projects/%s/notification-channels", r.client.projectID), nil, &channels); err != nil {
		response.Diagnostics.AddError("Unable to read notification channel", err.Error())
		return
	}
	for _, channel := range channels {
		if channel.ID == state.ID.ValueString() {
			state.Type = types.StringValue(channel.Type)
			state.Name = types.StringValue(channel.Name)
			state.Enabled = types.BoolValue(channel.Enabled)
			response.Diagnostics.Append(response.State.Set(ctx, state)...)
			return
		}
	}
	response.State.RemoveResource(ctx)
}
func (r *notificationChannelResource) Update(ctx context.Context, request resource.UpdateRequest, response *resource.UpdateResponse) {
	var plan notificationChannelModel
	response.Diagnostics.Append(request.Plan.Get(ctx, &plan)...)
	if response.Diagnostics.HasError() {
		return
	}
	r.setEnabled(ctx, plan.ID.ValueString(), plan.Enabled.ValueBool(), &response.Diagnostics)
	if !response.Diagnostics.HasError() {
		response.Diagnostics.Append(response.State.Set(ctx, plan)...)
	}
}
func (r *notificationChannelResource) Delete(ctx context.Context, request resource.DeleteRequest, response *resource.DeleteResponse) {
	var state notificationChannelModel
	response.Diagnostics.Append(request.State.Get(ctx, &state)...)
	if response.Diagnostics.HasError() {
		return
	}
	r.setEnabled(ctx, state.ID.ValueString(), false, &response.Diagnostics)
}
func (r *notificationChannelResource) setEnabled(ctx context.Context, id string, enabled bool, diagnostics *diag.Diagnostics) {
	if err := r.client.request(ctx, "PATCH", fmt.Sprintf("/api/v1/projects/%s/notification-channels/%s", r.client.projectID, id), map[string]bool{"enabled": enabled}, nil); err != nil {
		diagnostics.AddError("Unable to change notification channel state", err.Error())
	}
}
func optionalString(value types.String) any {
	if value.IsNull() || value.IsUnknown() {
		return nil
	}
	return value.ValueString()
}

var _ resource.Resource = &notificationChannelResource{}
var _ resource.ResourceWithConfigure = &notificationChannelResource{}
