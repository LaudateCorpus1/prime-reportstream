output "sa_primary_access_key" {
  value = azurerm_storage_account.storage_account.primary_access_key
}

output "candidate_access_key" {
  value = azurerm_storage_account.storage_account_candidate.primary_access_key
}

output "sa_primary_connection_string" {
  value = azurerm_storage_account.storage_account.primary_connection_string
}

output "sa_public_primary_web_endpoint" {
  value = azurerm_storage_account.storage_public.primary_web_endpoint
}

output "storage_account_id" {
  value = azurerm_storage_account.storage_account.id
}

output "storage_account" {
  value = azurerm_storage_account.storage_account
}

output "storage_public_id" {
  value = azurerm_storage_account.storage_public.id
}

output "storage_partner_id" {
  value = azurerm_storage_account.storage_partner.id
}

output "sa_partner_connection_string" {
  value = azurerm_storage_account.storage_partner.primary_connection_string
}