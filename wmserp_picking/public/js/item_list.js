// Item list > Actions > "Print QR Label Sheet" (item-level labels, for items that need no batch).
// Extends ERPNext's own list settings instead of replacing them.
frappe.listview_settings["Item"] = frappe.listview_settings["Item"] || {};
(function (settings) {
	const previous_onload = settings.onload;
	settings.onload = function (listview) {
		if (previous_onload) previous_onload(listview);
		wmserp.labels.add_sheet_action(listview, "Item");
	};
})(frappe.listview_settings["Item"]);
