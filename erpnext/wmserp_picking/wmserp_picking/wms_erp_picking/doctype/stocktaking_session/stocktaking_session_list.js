frappe.listview_settings["Stocktaking Session"] = {
	add_fields: ["status", "frozen", "counted_items", "total_items"],
	get_indicator(doc) {
		const colors = {
			Draft: "gray",
			Counting: "blue",
			"Manager Review": "orange",
			Recount: "orange",
			"Final Approval": "green",
			Reconciled: "green",
			Completed: "green",
			Cancelled: "red",
		};
		const label = doc.status === "Counting" ? `${doc.status} ${doc.counted_items || 0}/${doc.total_items || 0}` : doc.status;
		return [__(label), colors[doc.status] || "gray", "status,=," + doc.status];
	},
};
