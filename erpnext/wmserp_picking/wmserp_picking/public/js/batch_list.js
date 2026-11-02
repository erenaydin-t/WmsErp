// Batch list > Actions > "Print QR Label Sheet" (several batch labels per A4 page).
// Extends ERPNext's own list settings instead of replacing them.
frappe.listview_settings["Batch"] = frappe.listview_settings["Batch"] || {};
(function (settings) {
	const previous_onload = settings.onload;
	settings.onload = function (listview) {
		if (previous_onload) previous_onload(listview);
		wmserp.labels.add_sheet_action(listview, "Batch");
	};
})(frappe.listview_settings["Batch"]);
