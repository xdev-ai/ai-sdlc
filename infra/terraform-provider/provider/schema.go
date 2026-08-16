package provider

import "github.com/hashicorp/terraform-plugin-framework/provider/schema"

func providerSchema() schema.Schema {
	return schema.Schema{Attributes: map[string]schema.Attribute{
		"api_url":    schema.StringAttribute{Optional: true},
		"token":      schema.StringAttribute{Optional: true, Sensitive: true},
		"project_id": schema.StringAttribute{Optional: true},
	}}
}
