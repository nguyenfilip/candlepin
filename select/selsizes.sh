#!/bin/bash
function getCount() {
 echo $1
 psql -t -U candlepin candlepin -c "SELECT count(*) FROM $1" 
}


getCount cp_consumer_facts
getCount cp_content
getCount cp_activationkey_pool
getCount cp_cert_serial
getCount cp_consumer_guests
getCount cp_installed_products
getCount cp_consumer_type
getCount cp_content_modified_products
getCount cp_deleted_consumers
getCount cp_event
getCount cp_export_metadata
getCount cp_environment
getCount cp_key_pair
getCount cp_permission
getCount cp_product_certificate
getCount cp_product_dependent_products
getCount cp_product_pool_attribute
getCount cp_user
getCount cp_rules
getCount cp_subscription
getCount cp_role
getCount cp_pool_attribute
getCount cp_certificate
getCount cp_activation_key
getCount cp_role_users
getCount cp_env_content
getCount cp_owner
getCount cp_product_content
getCount cp_consumer
getCount cp_import_record
getCount cp_id_cert
getCount cp_pool_products
getCount cp_product_attribute
getCount cp_product
getCount cp_dist_version
getCount cp_dist_version_capability
getCount cp_upstream_consumer
getCount cp_consumer_capability
getCount cp_sub_derivedprods
getCount cp_cdn_certificate
getCount cp_cdn
getCount cp_import_upstream_consumer
getCount cp_subscription_products
getCount cp_pool_source_stack
getCount cp_consumer_guests_attributes
getCount cp_ent_certificate
getCount cp_content_override
getCount cp_branding
getCount cp_sub_branding
getCount cp_consumer_hypervisor
getCount cp_pool_source_sub
getCount cp_pool_branding
getCount cp_activationkey_product
getCount cp_consumer_content_tags
getCount cp_entitlement
getCount cp2_products
getCount cp2_environment_content
getCount cp2_activation_key_products
getCount cp2_pool_provided_products
getCount cp2_content_modified_products
getCount cp2_installed_products
getCount cp2_content
getCount cp2_pool_derprov_products
getCount cp2_product_attributes
getCount cp2_owner_products
getCount cp2_owner_content
getCount cp2_product_certificates
getCount cp2_product_content
getCount cp2_product_dependent_products
getCount cp_consumer_facts_lower
getCount cp2_pool_source_sub
getCount cp_pool
