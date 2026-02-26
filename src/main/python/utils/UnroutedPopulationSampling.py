import xml.etree.ElementTree as ET

def simplify_population(input_file, output_file):
    # Load the XML
    tree = ET.parse(input_file)
    root = tree.getroot()

    for person in root.findall('person'):
        for plan in person.findall('plan'):
            elements = list(plan)
            new_elements = []
            
            # Identify if the trip between two main activities contains PT
            # We iterate through the plan and 'collapse' multi-leg trips
            i = 0
            while i < len(elements):
                elem = elements[i]
                
                if elem.tag == 'activity':
                    # Skip 'pt interaction' activities entirely
                    if 'interaction' in elem.get('type', ''):
                        i += 1
                        continue
                    # Keep main/secondary activities (home, work, etc.)
                    new_elements.append(elem)
                    i += 1
                
                elif elem.tag == 'leg':
                    mode = elem.get('mode')
                    
                    # If this is a PT trip (starts with transit_walk or is pt)
                    # we want to replace the whole sequence with ONE pt leg
                    if mode in ['pt', 'transit_walk']:
                        # Create a clean leg
                        new_leg = ET.Element('leg')
                        new_leg.set('mode', 'pt')
                        # Note: No dep_time, no trav_time, no route tags
                        new_elements.append(new_leg)
                        
                        # Skip all subsequent legs and interactions until the next real activity
                        i += 1
                        while i < len(elements) and (elements[i].tag == 'leg' or 'interaction' in elements[i].get('type', '')):
                            i += 1
                    else:
                        # For non-PT legs (car, bike), just clean them
                        elem.attrib.pop('trav_time', None)
                        elem.attrib.pop('dep_time', None)
                        # Remove route sub-element
                        route = elem.find('route')
                        if route is not None:
                            elem.remove(route)
                        new_elements.append(elem)
                        i += 1

            # Clear and rebuild the plan
            plan.clear()
            for attr_name, attr_value in plan.attrib.items(): # Restore score/selected
                plan.set(attr_name, attr_value)
            plan.extend(new_elements)

    # Write back to file
    tree.write(output_file, encoding="utf-8", xml_declaration=True)
    print(f"Done! Created Berlin-style unrouted file: {output_file}")

# Run the script
simplify_population('E:\\MyFolder\\Upskill\\MatSIM\\matsim-Basic\\scenarios\\sioux-falls\\modified\\input\\population_10p.xml', 'E:\\MyFolder\\Upskill\\MatSIM\\matsim-Basic\\scenarios\\sioux-falls\\modified\\input\\population_10p_unrouted.xml')