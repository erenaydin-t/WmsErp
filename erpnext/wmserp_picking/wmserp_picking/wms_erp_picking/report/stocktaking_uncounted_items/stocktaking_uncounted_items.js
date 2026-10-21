// Filters of the Stocktaking Uncounted Items script report.
frappe.query_reports["Stocktaking Uncounted Items"] = {
	filters: [
		{
			fieldname: "session",
			label: __("Stocktaking Session"),
			fieldtype: "Link",
			options: "Stocktaking Session",
			reqd: 1,
			default: frappe.route_options && frappe.route_options.session,
		},
		{
			fieldname: "status",
			label: __("Row Status"),
			fieldtype: "Select",
			options: ["", "Not Counted", "Assigned", "Counting", "Recount Required"],
		},
		{ fieldname: "counter", label: __("Counter"), fieldtype: "Link", options: "User" },
		{ fieldname: "item_group", label: __("Item Group"), fieldtype: "Link", options: "Item Group" },
		{ fieldname: "location", label: __("Location contains"), fieldtype: "Data" },
	],
};
